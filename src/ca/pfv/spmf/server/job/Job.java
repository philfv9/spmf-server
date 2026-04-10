package ca.pfv.spmf.server.job;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 * ... (license header unchanged)
 */

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Immutable-identity value object that represents a single algorithm execution
 * request managed by the SPMF-Server.
 *
 * <p>
 * A {@code Job} is created via its constructor when a client submits an
 * algorithm request, and then transitions through the following lifecycle:
 * <pre>
 *   PENDING ──► RUNNING ──► DONE
 *                       └──► FAILED
 * </pre>
 *
 * The identity fields ({@link #jobId}, {@link #algorithmName},
 * {@link #parameters}, {@link #inputData}, {@link #submittedAt}) are set once
 * at construction and never change.
 *
 * <p>
 * <b>Thread safety and memory visibility:</b>
 * All mutable lifecycle fields are written inside {@code synchronized} blocks
 * on {@code this}. The {@link #status} field is also declared {@code volatile}
 * so that a reader thread can safely check {@link #isTerminal()} or
 * {@link #getStatus()} <em>without</em> acquiring the monitor — it will see
 * the latest value. However, to read multiple fields as a <em>consistent
 * snapshot</em> (e.g. {@code status + finishedAt} together) you must either
 * synchronise on the job instance externally or call the appropriate
 * snapshot getter provided here.
 *
 * <p>
 * The reason we keep {@code volatile} on {@code status} in addition to
 * synchronising writes: it lets high-frequency polling (e.g. the HTTP status
 * endpoint) avoid acquiring a monitor on every read.
 *
 * @author Philippe Fournier-Viger
 * @see JobStatus
 * @see JobManager
 */
public final class Job {

    // ── Identity fields (set once at construction, never changed) ──────────

    /** Universally unique identifier assigned to this job at creation time. */
    private final java.util.UUID jobId;

    /** Name of the SPMF algorithm to execute (as registered in the catalogue). */
    private final String algorithmName;

    /**
     * Immutable, ordered list of raw string parameter values supplied by the
     * API client.
     */
    private final List<String> parameters;

    /**
     * Raw text content of the input file supplied by the API client.
     * May be an empty string for algorithms that require no input file.
     */
    private final String inputData;

    /** Timestamp at which the job was submitted to the {@link JobManager}. */
    private final Instant submittedAt;

    // ── Mutable lifecycle fields ───────────────────────────────────────────
    //
    // Writes are performed inside synchronized(this) blocks so that all
    // fields that change in a single transition are visible atomically to any
    // thread that subsequently acquires the same monitor.
    //
    // status is ALSO volatile so that threads that only check the status (a
    // single field read — inherently atomic) don't need to acquire the monitor.

    /**
     * Current lifecycle status of the job.
     * Transitions: PENDING → RUNNING → DONE or FAILED.
     * <p>
     * Declared {@code volatile} so that a lightweight {@link #getStatus()}
     * or {@link #isTerminal()} check does not need to acquire the monitor.
     * All <em>writes</em> are still done inside {@code synchronized} blocks
     * to guarantee multi-field consistency.
     */
    private volatile JobStatus status;

    /** Timestamp at which the worker thread started executing the algorithm. */
    private volatile Instant startedAt;

    /** Timestamp at which the algorithm finished (successfully or with error). */
    private volatile Instant finishedAt;

    /**
     * Wall-clock duration of the algorithm execution in milliseconds.
     * Set to {@code 0} when the job fails before {@link #startedAt} is
     * recorded.
     */
    private volatile long executionTimeMs;

    /**
     * The output produced by the algorithm on success.
     * {@code null} until the job reaches {@link JobStatus#DONE}.
     */
    private volatile String resultData;

    /**
     * Human-readable error description set when the job reaches
     * {@link JobStatus#FAILED}. {@code null} otherwise.
     */
    private volatile String errorMessage;

    /**
     * Absolute path to the per-job working directory created by
     * {@link ca.pfv.spmf.server.spmfexecutor.SpmfProcessExecutor}.
     * {@code null} until the executor creates the directory.
     */
    private volatile String workDirPath;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Create a new job in the {@link JobStatus#PENDING} state.
     * <p>
     * A random {@link java.util.UUID} is assigned, the submission timestamp is
     * recorded, and the parameter list is defensively copied so that the caller
     * cannot mutate it after submission.
     *
     * @param algorithmName name of the SPMF algorithm to run; must not be
     *                      {@code null}
     * @param parameters    ordered list of parameter values; must not be
     *                      {@code null} (may be empty)
     * @param inputData     raw input file content; must not be {@code null}
     *                      (may be empty for algorithms with no input file)
     */
    public Job(String algorithmName, List<String> parameters, String inputData) {
        this.jobId         = java.util.UUID.randomUUID();
        this.algorithmName = algorithmName;
        this.parameters    = List.copyOf(parameters); // defensive copy
        this.inputData     = inputData;
        this.submittedAt   = Instant.now();
        this.status        = JobStatus.PENDING;
    }

    // ── Lifecycle transition methods ───────────────────────────────────────

    /**
     * Transition this job from {@link JobStatus#PENDING} to
     * {@link JobStatus#RUNNING} and record the start timestamp.
     * <p>
     * Must be called by the worker thread immediately before algorithm
     * execution begins.
     * <p>
     * All field writes are performed inside a {@code synchronized} block so
     * that any thread subsequently reading multiple fields under the same
     * monitor sees a consistent snapshot.
     */
    public void markRunning() {
        synchronized (this) {
            this.startedAt = Instant.now();
            this.status    = JobStatus.RUNNING; // written last for clarity
        }
    }

    /**
     * Transition this job to {@link JobStatus#DONE}, store the algorithm
     * output, and record the finish timestamp and wall-clock duration.
     * <p>
     * Must be called by the worker thread immediately after the algorithm
     * returns successfully.
     * <p>
     * All field writes are performed atomically under {@code synchronized(this)}
     * so that a polling thread reading both {@code status} and
     * {@code resultData} under the same monitor sees a consistent snapshot.
     *
     * @param resultData the content of the output file produced by the
     *                   algorithm; may be empty for algorithms with no output
     *                   file but must not be {@code null}
     */
    public void markDone(String resultData) {
        synchronized (this) {
            this.resultData      = resultData;
            this.finishedAt      = Instant.now();
            this.executionTimeMs = this.finishedAt.toEpochMilli()
                                 - this.startedAt.toEpochMilli();
            this.status          = JobStatus.DONE; // written last for visibility
        }
    }

    /**
     * Transition this job to {@link JobStatus#FAILED}, store the error
     * description, and record the finish timestamp and wall-clock duration.
     * <p>
     * If the job failed before {@link #markRunning()} was called (i.e.
     * {@link #startedAt} is {@code null}), the execution time is recorded as
     * {@code 0}.
     * <p>
     * All field writes are performed atomically under {@code synchronized(this)}.
     *
     * @param errorMessage human-readable description of the failure; should
     *                     not be {@code null}
     */
    public void markFailed(String errorMessage) {
        synchronized (this) {
            this.errorMessage    = errorMessage;
            this.finishedAt      = Instant.now();
            this.executionTimeMs = (startedAt != null)
                    ? this.finishedAt.toEpochMilli() - startedAt.toEpochMilli()
                    : 0L;
            this.status          = JobStatus.FAILED; // written last for visibility
        }
    }

    // ── Snapshot getter ────────────────────────────────────────────────────

    /**
     * Return a consistent, point-in-time snapshot of all mutable lifecycle
     * fields.
     * <p>
     * Use this method when you need to read more than one mutable field
     * (e.g. {@code status} <em>and</em> {@code resultData}) and must be
     * certain they reflect the same transition. For single-field reads
     * (e.g. a quick status poll) the individual getters are fine.
     *
     * @return an immutable {@link JobSnapshot} reflecting the state of the
     *         job at the instant this method was called
     */
    public JobSnapshot snapshot() {
        synchronized (this) {
            return new JobSnapshot(
                    status, startedAt, finishedAt,
                    executionTimeMs, resultData, errorMessage);
        }
    }

    /**
     * Immutable data-carrier returned by {@link Job#snapshot()}.
     * <p>
     * All fields are public and final; no getters are provided to keep the
     * class concise. Instances are created only by {@link Job#snapshot()}.
     */
    public static final class JobSnapshot {
        public final JobStatus status;
        public final Instant   startedAt;
        public final Instant   finishedAt;
        public final long      executionTimeMs;
        public final String    resultData;
        public final String    errorMessage;

        private JobSnapshot(JobStatus status, Instant startedAt,
                            Instant finishedAt, long executionTimeMs,
                            String resultData, String errorMessage) {
            this.status          = status;
            this.startedAt       = startedAt;
            this.finishedAt      = finishedAt;
            this.executionTimeMs = executionTimeMs;
            this.resultData      = resultData;
            this.errorMessage    = errorMessage;
        }
    }

    // ── Getters ────────────────────────────────────────────────────────────

    /**
     * Return the unique identifier of this job.
     *
     * @return job UUID; never {@code null}
     */
    public java.util.UUID getJobId() { return jobId; }

    /**
     * Return the unique identifier of this job as a plain string.
     * Convenience alternative to {@code getJobId().toString()}.
     *
     * @return job UUID string; never {@code null}
     */
    public String getJobIdString() { return jobId.toString(); }

    /**
     * Return the name of the SPMF algorithm associated with this job.
     *
     * @return algorithm name; never {@code null}
     */
    public String getAlgorithmName() { return algorithmName; }

    /**
     * Return the immutable list of parameter values supplied at submission.
     *
     * @return unmodifiable parameter list; never {@code null}
     */
    public List<String> getParameters() { return parameters; }

    /**
     * Return the raw input data supplied by the client.
     *
     * @return input data string; never {@code null}
     */
    public String getInputData() { return inputData; }

    /**
     * Return the timestamp at which this job was submitted.
     *
     * @return submission instant; never {@code null}
     */
    public Instant getSubmittedAt() { return submittedAt; }

    /**
     * Return the current lifecycle status of this job.
     * <p>
     * This is a single {@code volatile} read — safe for lightweight polling.
     * When you need a consistent multi-field view use {@link #snapshot()}.
     *
     * @return current {@link JobStatus}; never {@code null}
     */
    public JobStatus getStatus() { return status; }

    /**
     * Return the timestamp at which algorithm execution started, or
     * {@code null} if the job has not yet left the {@link JobStatus#PENDING}
     * state.
     *
     * @return start instant, or {@code null}
     */
    public Instant getStartedAt() { return startedAt; }

    /**
     * Return the timestamp at which the job finished (successfully or with
     * failure), or {@code null} if the job has not yet reached a terminal
     * state.
     *
     * @return finish instant, or {@code null}
     */
    public Instant getFinishedAt() { return finishedAt; }

    /**
     * Return the wall-clock execution time in milliseconds, measured from
     * {@link #getStartedAt()} to {@link #getFinishedAt()}.
     * Returns {@code 0} if the job failed before starting.
     *
     * @return execution duration in milliseconds
     */
    public long getExecutionTimeMs() { return executionTimeMs; }

    /**
     * Return the algorithm output data, or {@code null} if the job has not
     * yet reached {@link JobStatus#DONE}.
     *
     * @return result data string, or {@code null}
     */
    public String getResultData() { return resultData; }

    /**
     * Return the error description, or {@code null} if the job has not
     * reached {@link JobStatus#FAILED}.
     *
     * @return error message string, or {@code null}
     */
    public String getErrorMessage() { return errorMessage; }

    /**
     * Return the absolute path of the per-job working directory, or
     * {@code null} if the executor has not yet created it.
     *
     * @return working directory path, or {@code null}
     */
    public String getWorkDirPath() { return workDirPath; }

    /**
     * Set the absolute path of the per-job working directory.
     * Called by {@link ca.pfv.spmf.server.spmfexecutor.SpmfProcessExecutor}
     * once the directory has been created.
     *
     * @param path absolute path to the working directory; must not be
     *             {@code null}
     */
    public void setWorkDirPath(String path) { this.workDirPath = path; }

    // ── Derived helpers ────────────────────────────────────────────────────

    /**
     * Return the absolute path to the {@code console.log} file that captures
     * all child-process output for this job.
     * <p>
     * The file may not exist yet if the job has not started execution.
     *
     * @return absolute path to {@code console.log}, or {@code null} if the
     *         working directory has not been set yet
     */
    public String getConsolePath() {
        if (workDirPath == null) {
            return null;
        }
        return workDirPath + File.separator + "console.log";
    }

    /**
     * Return {@code true} if the job has reached a terminal state from which
     * it will never transition again.
     * <p>
     * Terminal states are {@link JobStatus#DONE} and {@link JobStatus#FAILED}.
     * This is a single {@code volatile} read — safe for lightweight polling
     * without acquiring a monitor.
     *
     * @return {@code true} if the job is done or failed
     */
    public boolean isTerminal() {
        return status == JobStatus.DONE || status == JobStatus.FAILED;
    }
}