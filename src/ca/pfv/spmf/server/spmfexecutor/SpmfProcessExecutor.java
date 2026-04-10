package ca.pfv.spmf.server.spmfexecutor;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 * ... (license header unchanged)
 */
import ca.pfv.spmf.algorithmmanager.AlgorithmManager;
import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.algorithmmanager.DescriptionOfParameter;
import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.job.Job;
import ca.pfv.spmf.server.util.ServerLogger;

/**
 * Executes a single SPMF algorithm in an isolated child JVM process and
 * captures all console output for later client retrieval.
 *
 * <p>
 * Each call to {@link #execute(Job)} performs the following steps:
 * <ol>
 *   <li>Creates a per-job working directory under
 *     {@link ServerConfig#getWorkDir()}.</li>
 *   <li>Writes the client-supplied input data to {@code input.txt}.</li>
 *   <li>Resolves and validates the algorithm and its parameters.</li>
 *   <li>Writes an {@code args.txt} file consumed by
 *     {@link SpmfChildProcess}.</li>
 *   <li>Spawns a child JVM with a configurable heap limit, redirecting
 *     both stdout and stderr to {@code console.log}.</li>
 *   <li>Waits for the child process with a hard timeout driven by
 *     {@link ServerConfig#getJobTimeoutMinutes()} (the execution timeout),
 *     <em>not</em> the TTL.</li>
 *   <li>Reads and returns {@code output.txt} on success.</li>
 * </ol>
 *
 * <b>Console log</b>: the file {@code console.log} in the job working
 * directory accumulates all child-process output and any timeout/exit-code
 * annotations appended by this class. Clients may retrieve it via the
 * {@code GET /api/jobs/{id}/console} endpoint.
 *
 * <b>Child JVM heap</b>: defaults to {@value #DEFAULT_CHILD_HEAP}.
 * Override by setting the {@code SPMF_CHILD_XMX} environment variable
 * (e.g. {@code SPMF_CHILD_XMX=2g}).
 *
 * @author Philippe Fournier-Viger
 * @see SpmfChildProcess
 * @see Job
 * @see ServerConfig
 */
public final class SpmfProcessExecutor {

    /** Logger for execution lifecycle events. */
    private static final Logger log = ServerLogger.get(SpmfProcessExecutor.class);

    // ── Constants ──────────────────────────────────────────────────────────

    /**
     * Default maximum heap size passed to the child JVM via {@code -Xmx}.
     * Can be overridden at runtime via the {@code SPMF_CHILD_XMX} environment
     * variable.
     */
    private static final String DEFAULT_CHILD_HEAP = "1g";

    /**
     * Time (milliseconds) the executor waits after sending SIGTERM before
     * escalating to SIGKILL.
     */
    private static final long GRACE_PERIOD_MS = 2_000L;

    /**
     * Time (milliseconds) the executor waits after sending SIGKILL before
     * declaring the process unkillable.
     */
    private static final long SIGKILL_WAIT_MS = 1_000L;

    /**
     * Resolved heap limit for child JVMs. Read once at class load time from
     * the {@code SPMF_CHILD_XMX} environment variable, falling back to
     * {@value #DEFAULT_CHILD_HEAP}.
     */
    private static final String CHILD_JVM_HEAP = resolveChildHeap();

    /**
     * Platform-specific path separator ({@code ":"} on Unix, {@code ";"} on
     * Windows). Computed once at class load time.
     */
    private static final String PATH_SEPARATOR = java.io.File.pathSeparator;

    // ── Instance state ─────────────────────────────────────────────────────

    /** Server configuration (port, work directory, timeouts, etc.). */
    private final ServerConfig config;

    /**
     * Algorithm catalogue used to look up descriptors by name.
     */
    private final SpmfCatalogue catalogue;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct an executor backed by the given server configuration and
     * algorithm catalogue.
     *
     * @param config    server configuration; must not be {@code null}
     * @param catalogue SPMF algorithm catalogue; must not be {@code null}
     */
    public SpmfProcessExecutor(ServerConfig config, SpmfCatalogue catalogue) {
        this.config    = config;
        this.catalogue = catalogue;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Execute the algorithm associated with {@code job} in a child JVM process.
     *
     * <p>
     * This method blocks the calling thread until the child process finishes
     * (or is killed by the timeout enforcer). It is intended to be called from
     * an executor thread managed by
     * {@link ca.pfv.spmf.server.job.JobManager}.
     *
     * <p>
     * The hard timeout is driven by {@link ServerConfig#getJobTimeoutMinutes()}
     * — the dedicated <em>execution</em> timeout — not by
     * {@link ServerConfig#getJobTtlMinutes()}, which controls how long a
     * <em>finished</em> job is retained in memory.
     *
     * @param job the job whose algorithm, input data, and parameters define the
     *            execution request; must not be {@code null}
     * @return the content of the output file produced by the algorithm, or an
     *         empty string if the algorithm declares no output file
     * @throws IllegalArgumentException if the algorithm name is unknown or a
     *                                  parameter fails validation
     * @throws RuntimeException         if the child process times out, is
     *                                  killed by a signal, or exits with a
     *                                  non-zero code
     * @throws Exception                on any I/O error during directory or
     *                                  file operations
     */
    public String execute(Job job) throws Exception {

        // ── Step 1: Create the per-job working directory ───────────────────
        Path jobDir = Paths.get(config.getWorkDir(), job.getJobIdString());
        Files.createDirectories(jobDir);
        job.setWorkDirPath(jobDir.toString());

        Path inputPath   = jobDir.resolve("input.txt");
        Path outputPath  = jobDir.resolve("output.txt");
        Path argsPath    = jobDir.resolve("args.txt");
        Path consolePath = jobDir.resolve("console.log");

        // ── Step 2: Persist the client-supplied input data ─────────────────
        Files.writeString(inputPath, job.getInputData());
        log.fine("Job " + job.getJobIdString()
                + " — input written to: " + inputPath);

        // ── Step 3: Resolve the algorithm descriptor ───────────────────────
        DescriptionOfAlgorithm algorithm =
                AlgorithmManager.getInstance()
                                .getDescriptionOfAlgorithm(job.getAlgorithmName());

        if (algorithm == null) {
            throw new IllegalArgumentException(
                    "No algorithm named '" + job.getAlgorithmName()
                    + "' found in the SPMF catalogue.");
        }

        // ── Step 4: Determine effective input / output file paths ──────────
        String inputFile  = (algorithm.getInputFileTypes()  == null) ? null
                : inputPath.toAbsolutePath().toString();
        String outputFile = (algorithm.getOutputFileTypes() == null) ? null
                : outputPath.toAbsolutePath().toString();

        // ── Step 5: Validate and prepare the parameter array ──────────────
        List<String> paramList  = job.getParameters();
        String[]     parameters = paramList.toArray(new String[0]);

        validateParameters(algorithm, parameters);

        // ── Step 6: Write the args-file consumed by SpmfChildProcess ──────
        List<String> argsLines = new ArrayList<>();
        argsLines.add(job.getAlgorithmName());
        argsLines.add(inputFile  != null ? inputFile  : "");
        argsLines.add(outputFile != null ? outputFile : "");
        for (String param : parameters) {
            argsLines.add(param);
        }
        Files.write(argsPath, argsLines);

        // ── Step 7: Spawn the child JVM process ───────────────────────────
        // Use the dedicated EXECUTION timeout, not the retention TTL.
        long timeoutMs = (long) config.getJobTimeoutMinutes() * 60_000L;

        log.info("Job " + job.getJobIdString()
                + " — spawning child JVM for '" + job.getAlgorithmName()
                + "' (heap: " + CHILD_JVM_HEAP
                + ", timeout: " + config.getJobTimeoutMinutes() + " min)");

        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-Xmx" + CHILD_JVM_HEAP,
                "-cp", resolveAbsoluteClasspath(),
                SpmfChildProcess.class.getName(),
                argsPath.toAbsolutePath().toString()
        );

        pb.directory(jobDir.toFile());
        pb.redirectOutput(consolePath.toFile());
        pb.redirectError(ProcessBuilder.Redirect.appendTo(consolePath.toFile()));

        Process process = pb.start();

        // ── Step 8: Wait with hard timeout enforcement ─────────────────────
        boolean finished = waitForProcessWithTimeout(process, timeoutMs, job);

        if (!finished) {
            appendToConsole(consolePath, "[TIMEOUT] Algorithm exceeded "
                    + config.getJobTimeoutMinutes()
                    + " minute(s) and was forcibly killed.");
            throw new RuntimeException(
                    "Algorithm timed out after " + config.getJobTimeoutMinutes()
                    + " minute(s). Process was forcibly killed.");
        }

        // ── Step 9: Inspect the process exit code ─────────────────────────
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            appendToConsole(consolePath, "[EXIT CODE] " + exitCode);

            if (exitCode == 143 || exitCode == 137 || exitCode == -9) {
                throw new RuntimeException(
                        "Algorithm process was killed by the OS (signal).");
            }
            throw new RuntimeException(
                    "Algorithm process exited with non-zero code " + exitCode
                    + ". Inspect console.log for details.");
        }

        // ── Step 10: Read and return the output file ───────────────────────
        if (outputFile == null) {
            log.info("Job " + job.getJobIdString()
                    + " — algorithm declares no output file.");
            return "";
        }

        if (!Files.exists(outputPath)) {
            throw new RuntimeException(
                    "Algorithm succeeded (exit 0) but produced no output file at: "
                    + outputPath);
        }

        String result = Files.readString(outputPath);
        log.fine("Job " + job.getJobIdString()
                + " — output read: " + result.length() + " character(s).");
        return result;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Validate {@code parameters} against the algorithm's formal parameter
     * specification.
     *
     * @param algorithm  the resolved algorithm descriptor
     * @param parameters the array of raw string values to validate
     * @throws IllegalArgumentException if any parameter is missing, empty
     *                                  where mandatory, or of the wrong type
     */
    private void validateParameters(DescriptionOfAlgorithm algorithm,
                                    String[] parameters) {

        DescriptionOfParameter[] paramDescs = algorithm.getParametersDescription();
        if (paramDescs == null) {
            return;
        }

        for (int i = 0; i < paramDescs.length; i++) {
            DescriptionOfParameter paramDesc = paramDescs[i];

            if (i == parameters.length) {
                if (!paramDesc.isOptional) {
                    throw new IllegalArgumentException(
                            "The " + ordinal(i + 1)
                            + " parameter '" + paramDesc.name
                            + "' is mandatory but was not supplied.");
                }
                break;
            }

            String value = parameters[i];

            if (value == null || value.isEmpty()) {
                if (!paramDesc.isOptional) {
                    throw new IllegalArgumentException(
                            "The " + ordinal(i + 1)
                            + " parameter '" + paramDesc.name
                            + "' is mandatory and cannot be empty.");
                }
            } else {
                if (!algorithm.isParameterOfCorrectType(value, i)) {
                    throw new IllegalArgumentException(
                            "The " + ordinal(i + 1)
                            + " parameter '" + paramDesc.name
                            + "' has an incorrect type: '" + value + "'.");
                }
            }
        }
    }

    /**
     * Wait for the child process to finish, enforcing a hard timeout.
     *
     * @param process   the child process to monitor
     * @param timeoutMs maximum time (milliseconds) to wait for natural exit
     * @param job       the associated job (used only for log messages)
     * @return {@code true} if the process terminated; {@code false} if it
     *         survived SIGKILL
     */
    private boolean waitForProcessWithTimeout(Process process,
                                              long timeoutMs,
                                              Job job) {
        // Phase 1: natural wait
        try {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Phase 2: SIGTERM + grace period
        log.warning("Job " + job.getJobIdString()
                + " exceeded timeout of " + (timeoutMs / 1_000)
                + "s — sending SIGTERM.");
        process.destroy();

        try {
            if (process.waitFor(GRACE_PERIOD_MS, TimeUnit.MILLISECONDS)) {
                log.info("Job " + job.getJobIdString()
                        + " terminated gracefully after SIGTERM.");
                return true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Phase 3: SIGKILL
        log.severe("Job " + job.getJobIdString()
                + " still alive after grace period — sending SIGKILL.");
        process.destroyForcibly();

        try {
            if (process.waitFor(SIGKILL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.warning("Job " + job.getJobIdString()
                        + " killed with SIGKILL.");
                return true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.severe("Job " + job.getJobIdString()
                + " is STILL ALIVE after SIGKILL — cannot terminate process.");
        return false;
    }

    /**
     * Append a single annotated line to the job's {@code console.log} file.
     *
     * @param consolePath path to the {@code console.log} file
     * @param message     the annotation to append
     */
    private static void appendToConsole(Path consolePath, String message) {
        try (FileWriter fw = new FileWriter(consolePath.toFile(), true)) {
            fw.write('\n');
            fw.write(message);
            fw.write('\n');
        } catch (IOException e) {
            log.warning("Could not append to console.log at '"
                    + consolePath + "': " + e.getMessage());
        }
    }

    /**
     * Resolve the heap limit to use for child JVMs.
     *
     * @return heap size string suitable for the {@code -Xmx} JVM flag
     */
    private static String resolveChildHeap() {
        String env = System.getenv("SPMF_CHILD_XMX");
        return (env != null && !env.isBlank()) ? env : DEFAULT_CHILD_HEAP;
    }

    /**
     * Return the English ordinal string for a positive integer.
     *
     * @param n a positive integer
     * @return ordinal string (e.g. "1st", "2nd", "3rd", "4th")
     */
    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) { return n + "th"; }
        switch (n % 10) {
            case 1:  return n + "st";
            case 2:  return n + "nd";
            case 3:  return n + "rd";
            default: return n + "th";
        }
    }

    /**
     * Resolve all entries in the current JVM classpath to absolute paths.
     *
     * <p>
     * The child JVM is launched from a per-job working directory that is
     * different from the server's working directory, so relative classpath
     * entries (e.g. {@code "spmf.jar"}) would not be found. This method
     * converts every entry to its absolute canonical form.
     *
     * <p>
     * <b>Note on splitting:</b> we use {@link java.io.File#pathSeparator}
     * directly as a literal string — not a regex — because {@code String.split}
     * interprets its argument as a regex and {@code ":"} on Unix and
     * {@code ";"} on Windows are both safe regex literals. Using
     * {@code Pattern.quote(PATH_SEPARATOR)} would also be correct.
     *
     * @return classpath string with all entries as absolute paths
     */
    private static String resolveAbsoluteClasspath() {
        String rawCp = System.getProperty("java.class.path");
        if (rawCp == null || rawCp.isBlank()) {
            return "";
        }

        // Split on the literal path separator — safe as a regex literal
        // for both ":" (Unix) and ";" (Windows).
        String[] entries = rawCp.split(
                PATH_SEPARATOR.equals(";") ? ";" : ":",
                -1);

        StringBuilder resolved = new StringBuilder();
        for (String entry : entries) {
            if (!entry.isBlank()) {
                if (resolved.length() > 0) {
                    resolved.append(PATH_SEPARATOR);
                }
                try {
                    resolved.append(
                        Paths.get(entry)
                             .toAbsolutePath()
                             .normalize()
                             .toString());
                } catch (Exception e) {
                    resolved.append(entry); // fallback: use as-is
                }
            }
        }
        return resolved.toString();
    }
}