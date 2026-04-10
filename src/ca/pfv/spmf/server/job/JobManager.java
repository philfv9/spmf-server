package ca.pfv.spmf.server.job;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 * ... (license header unchanged)
 */
import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.spmfexecutor.SpmfProcessExecutor;
import ca.pfv.spmf.server.util.FileUtil;
import ca.pfv.spmf.server.util.ServerLogger;

/**
 * Central coordinator for the SPMF-Server job lifecycle.
 *
 * <p>
 * {@code JobManager} is responsible for:
 * <ul>
 *   <li><b>Submission</b> — creating {@link Job} objects and dispatching them
 *     to an internal {@link ThreadPoolExecutor} for asynchronous execution.</li>
 *   <li><b>Tracking</b> — maintaining a {@link ConcurrentHashMap} of all live
 *     jobs, keyed by {@link UUID}.</li>
 *   <li><b>Retrieval</b> — looking up individual jobs or returning snapshots
 *     of the full job collection.</li>
 *   <li><b>Deletion</b> — removing jobs from the map and cleaning up their
 *     on-disk working directories.</li>
 *   <li><b>Shutdown</b> — orderly draining of the thread pool on server
 *     stop.</li>
 * </ul>
 *
 * <b>Thread pool configuration</b> (from {@link ServerConfig}):
 * <ul>
 *   <li>{@code executor.coreThreads} — always-alive worker threads.</li>
 *   <li>{@code executor.maxThreads} — maximum threads under peak load.</li>
 *   <li>{@code job.maxQueueSize} — bounded submission queue; excess
 *     submissions are rejected with a {@link RejectedExecutionException}.</li>
 * </ul>
 *
 * <b>Execution model</b>: each job is run by
 * {@link SpmfProcessExecutor#execute(Job)} inside a pool worker thread. The
 * algorithm itself executes in a separate child JVM process spawned by
 * {@link SpmfProcessExecutor}, so a misbehaving algorithm cannot corrupt the
 * server's heap or thread state.
 *
 * @author Philippe Fournier-Viger
 * @see Job
 * @see JobStatus
 * @see JobCleaner
 * @see SpmfProcessExecutor
 */
public final class JobManager {

    /** Logger for job lifecycle events. */
    private static final Logger log = ServerLogger.get(JobManager.class);

    /**
     * Maximum time (seconds) to wait for running jobs to finish during
     * {@link #shutdown()} before forcibly terminating the pool.
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;

    /**
     * Dedicated {@link ThreadGroup} for all algorithm worker threads.
     * <p>
     * Grouping threads under a named group makes heap-dump analysis and
     * thread-monitor tools (e.g. {@code jstack}) easier to read — all worker
     * threads appear as {@code "spmf-worker-N"} under {@code "spmf-algorithms"}.
     * <p>
     * The overridden {@link ThreadGroup#uncaughtException(Thread, Throwable)}
     * prevents {@link Error}s (such as {@link OutOfMemoryError}) thrown inside
     * a worker from propagating to the parent thread group and potentially
     * terminating the JVM. Error reporting is handled by the per-job
     * {@code catch (Throwable)} block in {@link #runJob} instead.
     */
    private static final ThreadGroup ALGO_GROUP =
            new ThreadGroup("spmf-algorithms") {
                /**
                 * Absorb uncaught throwables so that a single algorithm's
                 * {@link OutOfMemoryError} cannot kill the server process.
                 * The worker's try/catch block is responsible for marking the
                 * job as failed; this override is a last-resort safety net.
                 */
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    System.err.println(
                            "[SPMF-Server] Uncaught throwable on algorithm thread '"
                            + thread.getName() + "': " + throwable);
                    // Intentionally do NOT call super.
                }
            };

    // ── Instance state ─────────────────────────────────────────────────────

    /**
     * Live job store — maps each job's {@link UUID} to its {@link Job} object.
     * {@link ConcurrentHashMap} provides thread-safe put/get/remove without
     * explicit locking.
     */
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();

    /**
     * Thread pool that executes submitted jobs asynchronously. Bounded by
     * {@link ServerConfig#getCoreThreads()} / {@link ServerConfig#getMaxThreads()}
     * and backed by a fixed-capacity {@link ArrayBlockingQueue}.
     */
    private final ThreadPoolExecutor executor;

    /** Server configuration (thread counts, queue size, work directory, etc.). */
    private final ServerConfig config;

    /** SPMF algorithm catalogue passed to each {@link SpmfProcessExecutor}. */
    private final SpmfCatalogue catalogue;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct and start a {@code JobManager} with the given configuration.
     * <p>
     * The internal thread pool is created immediately; the server is ready to
     * accept job submissions as soon as the constructor returns.
     *
     * @param config    server configuration; must not be {@code null}
     * @param catalogue SPMF algorithm catalogue; must not be {@code null}
     */
    public JobManager(ServerConfig config, SpmfCatalogue catalogue) {
        this.config    = config;
        this.catalogue = catalogue;

        // Bounded queue — submissions beyond maxQueueSize are rejected
        BlockingQueue<Runnable> queue =
                new ArrayBlockingQueue<>(config.getMaxQueueSize());

        this.executor = new ThreadPoolExecutor(
                config.getCoreThreads(),
                config.getMaxThreads(),
                60L, TimeUnit.SECONDS,       // keep-alive for idle excess threads
                queue,
                r -> {
                    Thread t = new Thread(ALGO_GROUP, r);
                    t.setName("spmf-worker-" + t.getId());
                    t.setDaemon(true);        // do not block JVM shutdown
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy() // reject when queue is full
        );

        log.info("JobManager started (coreThreads=" + config.getCoreThreads()
                + ", maxThreads=" + config.getMaxThreads()
                + ", queueSize=" + config.getMaxQueueSize() + ").");
    }

    // ── Job submission ─────────────────────────────────────────────────────

    /**
     * Create a new job, register it in the job store, and submit it to the
     * thread pool for asynchronous execution.
     * <p>
     * The job is immediately visible to callers of {@link #getJob(String)} with
     * status {@link JobStatus#PENDING}. The worker thread transitions it through
     * {@link JobStatus#RUNNING} and finally to {@link JobStatus#DONE} or
     * {@link JobStatus#FAILED}.
     *
     * @param algorithmName name of the SPMF algorithm to run; must not be
     *                      {@code null}
     * @param parameters    ordered parameter values; must not be {@code null}
     * @param inputData     raw input file content; must not be {@code null}
     * @return the newly created {@link Job} in {@link JobStatus#PENDING} state
     * @throws RejectedExecutionException if the submission queue is full
     *         (HTTP 503 should be returned to the client in this case)
     */
    public Job submit(String algorithmName,
                      List<String> parameters,
                      String inputData) {

        Job job = new Job(algorithmName, parameters, inputData);
        jobs.put(job.getJobId(), job);

        log.info("Job submitted: " + job.getJobIdString()
                + "  algorithm=" + algorithmName);

        try {
            executor.submit(() -> runJob(job));
        } catch (RejectedExecutionException e) {
            // The queue is full — remove the job we just registered and
            // re-throw so the HTTP handler can return 503.
            jobs.remove(job.getJobId());
            log.warning("Job rejected (queue full): " + job.getJobIdString());
            throw e;
        }

        return job;
    }

    // ── Job lookup and management ──────────────────────────────────────────

    /**
     * Look up a job by its string UUID.
     *
     * @param jobIdStr the job ID as returned by {@link Job#getJobIdString()}
     * @return the {@link Job}, or {@code null} if the ID is unknown or not a
     *         valid UUID
     */
    public Job getJob(String jobIdStr) {
        try {
            return jobs.get(UUID.fromString(jobIdStr));
        } catch (IllegalArgumentException e) {
            return null; // malformed UUID — treat as not found
        }
    }

    /**
     * Remove a job from the store and delete its on-disk working directory.
     * <p>
     * This method is safe to call on a running job; the job entry is removed
     * from the map immediately, but any child process already in flight
     * continues to completion in its own JVM.
     *
     * @param jobIdStr the job ID to delete
     * @return {@code true} if the job was found and removed; {@code false} if
     *         the ID is unknown or not a valid UUID
     */
    public boolean deleteJob(String jobIdStr) {
        try {
            UUID uuid = UUID.fromString(jobIdStr);
            Job  job  = jobs.remove(uuid);

            if (job == null) {
                return false; // job not found
            }

            // Delete the per-job working directory (input, output, console.log)
            if (job.getWorkDirPath() != null) {
                FileUtil.deleteDirectory(job.getWorkDirPath());
            }

            log.info("Job deleted: " + jobIdStr);
            return true;

        } catch (IllegalArgumentException e) {
            return false; // malformed UUID
        }
    }

    /**
     * Return a point-in-time snapshot of all jobs currently in the store.
     * <p>
     * The returned list is a defensive copy; changes to the map after this
     * call are not reflected in the list.
     *
     * @return list of all known {@link Job} objects; never {@code null}
     */
    public Collection<Job> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    // ── Thread pool metrics ────────────────────────────────────────────────

    /**
     * Return the number of jobs currently being executed by a worker thread.
     *
     * @return active (running) job count
     */
    public int activeJobCount() { return executor.getActiveCount(); }

    /**
     * Return the number of jobs waiting in the submission queue.
     *
     * @return queued (pending) job count
     */
    public int queuedJobCount() { return executor.getQueue().size(); }

    /**
     * Return the total number of jobs currently held in the job store
     * (pending + running + terminal).
     *
     * @return total tracked job count
     */
    public int totalJobCount() { return jobs.size(); }

    // ── Shutdown ───────────────────────────────────────────────────────────

    /**
     * Initiate an orderly shutdown of the thread pool.
     * <p>
     * Waits up to {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds for running jobs
     * to finish before forcibly terminating all worker threads. This method
     * blocks the calling thread until the pool has fully stopped.
     * <p>
     * Called by {@link ca.pfv.spmf.server.ServerMain#stopServer()} during
     * server shutdown.
     */
    public void shutdown() {
        log.info("JobManager shutting down — waiting up to "
                + SHUTDOWN_TIMEOUT_SECONDS + "s for running jobs...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS,
                                           TimeUnit.SECONDS)) {
                log.warning("Timeout elapsed — forcibly terminating worker threads.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt(); // restore interrupt flag
        }
        log.info("JobManager stopped.");
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Execute the algorithm associated with {@code job} on the calling worker
     * thread, updating the job's status throughout.
     *
     * <p>
     * This method is the body of the {@link Runnable} submitted to the thread
     * pool by {@link #submit}. It delegates actual algorithm execution to
     * {@link SpmfProcessExecutor#execute(Job)}.
     *
     * <p>
     * <b>Throwable catch:</b> we catch {@link Throwable} (not just
     * {@link Exception}) here because the worker thread runs inside the server
     * JVM (not the child JVM). If the executor or file-I/O code triggers an
     * {@link OutOfMemoryError} or {@link StackOverflowError}, we still need to
     * mark the job as failed so that polling clients receive a definitive
     * answer rather than seeing the job stuck in {@code RUNNING} forever.
     *
     * @param job the job to execute; must not be {@code null}
     */
    private void runJob(Job job) {
        job.markRunning();
        log.info("Job running: " + job.getJobIdString());

        try {
            SpmfProcessExecutor spmfExecutor =
                    new SpmfProcessExecutor(config, catalogue);
            String result = spmfExecutor.execute(job);
            job.markDone(result);
            log.info("Job done: " + job.getJobIdString()
                    + " executionTime=" + job.getExecutionTimeMs() + " ms");

        } catch (Throwable t) {
            // Catch Throwable — not just Exception — so that OOM / SOF errors
            // inside the server-side executor code still result in a terminal
            // job state rather than leaving the job stuck in RUNNING forever.
            String message = buildErrorMessage(t);
            job.markFailed(message);
            log.severe("Job failed: " + job.getJobIdString()
                    + " reason=" + message);
        }
    }

    /**
     * Convert a {@link Throwable} into a concise, human-readable error string
     * suitable for inclusion in the job status response returned to the client.
     *
     * <p>
     * Provides specific guidance for the most common JVM-level failures
     * ({@link OutOfMemoryError}, {@link StackOverflowError},
     * {@link ThreadDeath}) and falls back to the exception class name plus
     * message for all other cases.
     *
     * @param t the throwable to describe; must not be {@code null}
     * @return a non-null, non-blank error message string
     */
    private static String buildErrorMessage(Throwable t) {
        if (t instanceof OutOfMemoryError) {
            return "Algorithm ran out of memory (OutOfMemoryError). "
                    + "Increase JVM heap with -Xmx (e.g. -Xmx2g) or "
                    + "reduce the input size.";
        }
        if (t instanceof StackOverflowError) {
            return "Algorithm caused a stack overflow (StackOverflowError). "
                    + "The input may be too large or the algorithm too deeply recursive.";
        }
        if (t instanceof ThreadDeath) {
            return "Algorithm thread was forcibly stopped.";
        }

        // General case — include class name for diagnostics
        String message = t.getMessage();
        return (message != null && !message.isBlank())
                ? t.getClass().getSimpleName() + ": " + message
                : t.getClass().getName();
    }
}