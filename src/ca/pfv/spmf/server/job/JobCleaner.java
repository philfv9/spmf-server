package ca.pfv.spmf.server.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 * ... (license header unchanged)
 */
import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.util.ServerLogger;

/**
 * Background service that periodically evicts expired jobs from the
 * {@link JobManager} to prevent unbounded memory and disk growth.
 *
 * <p>
 * A job is eligible for eviction when <em>all</em> of the following are true:
 * <ul>
 *   <li>It has reached a terminal state ({@link JobStatus#DONE} or
 *     {@link JobStatus#FAILED}).</li>
 *   <li>Its {@link Job#getFinishedAt() finishedAt} timestamp is non-null.</li>
 *   <li>The time elapsed since {@code finishedAt} exceeds the configured
 *     TTL ({@link ServerConfig#getJobTtlMinutes()} minutes).</li>
 * </ul>
 *
 * The cleaner runs on a single daemon thread (named {@code "job-cleaner"})
 * managed by a {@link ScheduledExecutorService}. The cleaning pass is
 * triggered once per minute.
 *
 * <p>
 * <b>Robustness:</b> the {@link #clean()} method wraps all work in a
 * {@code try/catch(Throwable)} so that a transient filesystem error or an
 * unexpected runtime exception can <em>never</em> silently cancel the
 * recurring schedule. Without this guard, any unchecked exception escaping
 * from a {@link ScheduledExecutorService} task causes the task to be silently
 * de-scheduled — a subtle and hard-to-diagnose failure mode.
 *
 * <p>
 * Call {@link #start()} after constructing the cleaner and {@link #stop()} as
 * part of the server shutdown sequence.
 *
 * @author Philippe Fournier-Viger
 * @see JobManager
 * @see ServerConfig#getJobTtlMinutes()
 */
public final class JobCleaner {

    /** Logger for eviction events. */
    private static final Logger log = ServerLogger.get(JobCleaner.class);

    /**
     * Initial delay (minutes) before the first cleaning pass runs after
     * {@link #start()} is called. A short delay avoids unnecessary work
     * during the server warm-up phase.
     */
    private static final long INITIAL_DELAY_MINUTES = 1L;

    /**
     * Interval (minutes) between consecutive cleaning passes.
     * One minute is granular enough given that TTLs are expressed in minutes.
     */
    private static final long INTERVAL_MINUTES = 1L;

    // ── Dependencies ───────────────────────────────────────────────────────

    /** The job store from which expired jobs are removed. */
    private final JobManager jobManager;

    /** Server configuration — provides the TTL setting. */
    private final ServerConfig config;

    /**
     * Single-thread scheduled executor that drives the periodic cleaning pass.
     * The thread is marked daemon so it does not prevent JVM shutdown.
     */
    private final ScheduledExecutorService scheduler;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct a {@code JobCleaner} but do not start it yet.
     * Call {@link #start()} to activate the periodic cleaning schedule.
     *
     * @param jobManager the job manager whose expired jobs will be evicted;
     *                   must not be {@code null}
     * @param config     server configuration used to read the TTL setting;
     *                   must not be {@code null}
     */
    public JobCleaner(JobManager jobManager, ServerConfig config) {
        this.jobManager = jobManager;
        this.config     = config;

        // Named daemon thread so it does not block JVM shutdown
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-cleaner");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Activate the periodic cleaning schedule.
     *
     * <p>
     * After a short initial delay of {@value #INITIAL_DELAY_MINUTES} minute(s),
     * {@link #safeClean()} is called every {@value #INTERVAL_MINUTES} minute(s).
     * Calling {@code start()} more than once has no additional effect because
     * the underlying {@link ScheduledExecutorService} is created at construction
     * and cannot be reused after {@link #stop()}.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(
                this::safeClean,
                INITIAL_DELAY_MINUTES,
                INTERVAL_MINUTES,
                TimeUnit.MINUTES);

        log.info("JobCleaner started (TTL=" + config.getJobTtlMinutes()
                + " min, interval=" + INTERVAL_MINUTES + " min).");
    }

    /**
     * Stop the periodic cleaning schedule immediately.
     * <p>
     * Any cleaning pass that is currently in progress may be interrupted.
     * This method is idempotent — calling it multiple times is safe.
     */
    public void stop() {
        scheduler.shutdownNow();
        log.info("JobCleaner stopped.");
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Wrapper around {@link #clean()} that catches <em>all</em>
     * {@link Throwable}s and logs them without re-throwing.
     *
     * <p>
     * A {@link ScheduledExecutorService} silently cancels a recurring task if
     * its {@link Runnable} throws any unchecked exception or error. By
     * catching everything here we guarantee that a transient failure (e.g. a
     * filesystem error during directory deletion) can never permanently disable
     * the cleaner.
     */
    private void safeClean() {
        try {
            clean();
        } catch (Throwable t) {
            // Log but never propagate — propagation would cancel the schedule.
            log.warning("JobCleaner encountered an unexpected error during "
                    + "the cleaning pass (schedule will continue): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Execute a single eviction pass over all known jobs.
     *
     * <p>
     * Computes the expiry cutoff as {@code now - TTL} and removes every
     * terminal job whose {@code finishedAt} timestamp falls before the cutoff.
     * Removal is delegated to {@link JobManager#deleteJob(String)}, which also
     * deletes the per-job working directory from disk.
     */
    private void clean() {
        // Compute the oldest finish time that is still within the TTL window
        Instant cutoff = Instant.now()
                .minus(config.getJobTtlMinutes(), ChronoUnit.MINUTES);

        int removed = 0;

        for (Job job : jobManager.getAllJobs()) {
            // Only evict jobs in a terminal state with a recorded finish time
            if (job.isTerminal()
                    && job.getFinishedAt() != null
                    && job.getFinishedAt().isBefore(cutoff)) {

                jobManager.deleteJob(job.getJobIdString());
                removed++;
            }
        }

        if (removed > 0) {
            log.info("JobCleaner evicted " + removed + " expired job(s).");
        }
    }
}