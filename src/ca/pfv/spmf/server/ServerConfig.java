package ca.pfv.spmf.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 * ... (license header unchanged)
 */
import ca.pfv.spmf.server.util.ServerLogger;

/**
 * Immutable value-object that holds all server configuration parameters.
 *
 * <p>
 * Configuration is loaded from a standard Java {@code .properties} file via
 * {@link #load(String)}. Any property that is absent or unparseable falls back
 * to a hard-coded sensible default so the server can always start without a
 * config file. All integer values are validated to be within reasonable bounds
 * after parsing.
 *
 * <p>
 * <b>Supported properties and their defaults:</b>
 * <pre>
 *   server.port          = 8585
 *   server.host          = 0.0.0.0
 *   executor.coreThreads = 4
 *   executor.maxThreads  = 8
 *   job.ttlMinutes       = 30
 *   job.timeoutMinutes   = 10
 *   job.maxQueueSize     = 100
 *   work.dir             = ./spmf-work
 *   input.maxSizeMb      = 50
 *   security.apiKey      = (empty — disabled)
 *   log.level            = INFO
 *   log.file             = ./logs/spmf-server.log
 * </pre>
 *
 * @author Philippe Fournier-Viger
 * @see ServerMain
 */
public final class ServerConfig {

    /** Logger for configuration loading events. */
    private static final Logger log = ServerLogger.get(ServerConfig.class);

    // ── Default values ─────────────────────────────────────────────────────

    /** Default TCP port the HTTP server listens on. */
    private static final int DEFAULT_PORT = 8585;

    /** Default bind address (all interfaces). */
    private static final String DEFAULT_HOST = "0.0.0.0";

    /** Default number of always-alive threads in the executor pool. */
    private static final int DEFAULT_CORE_THREADS = 4;

    /** Default maximum threads the executor pool may create under load. */
    private static final int DEFAULT_MAX_THREADS = 8;

    /** Default time-to-live for completed/failed jobs, in minutes. */
    private static final int DEFAULT_TTL_MINUTES = 30;

    /**
     * Default maximum wall-clock time an algorithm is allowed to run before
     * the child JVM process is killed, in minutes.
     * <p>
     * This is intentionally separate from {@link #DEFAULT_TTL_MINUTES} —
     * the TTL controls how long a <em>finished</em> job is retained in
     * memory, while the execution timeout controls how long a
     * <em>running</em> job is allowed to execute.
     */
    private static final int DEFAULT_JOB_TIMEOUT_MINUTES = 10;

    /** Default maximum number of jobs that may wait in the execution queue. */
    private static final int DEFAULT_QUEUE_SIZE = 100;

    /** Default directory used for temporary job input/output files. */
    private static final String DEFAULT_WORK_DIR = "./spmf-work";

    /** Default maximum size (MB) accepted for a single input file upload. */
    private static final int DEFAULT_MAX_MB = 50;

    /** Default logging level (JUL level name). */
    private static final String DEFAULT_LOG_LEVEL = "INFO";

    /** Default path for the rotating log file. */
    private static final String DEFAULT_LOG_FILE = "./logs/spmf-server.log";

    // ── Bounds for integer validation ──────────────────────────────────────

    private static final int MIN_PORT            = 1;
    private static final int MAX_PORT            = 65535;
    private static final int MIN_THREADS         = 1;
    private static final int MAX_THREADS_LIMIT   = 1024;
    private static final int MIN_TTL_MINUTES     = 1;
    private static final int MAX_TTL_MINUTES     = 60 * 24 * 7; // 1 week
    private static final int MIN_TIMEOUT_MINUTES = 1;
    private static final int MAX_TIMEOUT_MINUTES = 60 * 24;     // 1 day
    private static final int MIN_QUEUE_SIZE      = 1;
    private static final int MAX_QUEUE_SIZE      = 100_000;
    private static final int MIN_MAX_MB          = 1;
    private static final int MAX_MAX_MB          = 10_240;      // 10 GB

    // ── Configuration fields ───────────────────────────────────────────────

    /** TCP port the HTTP server binds to. */
    private final int port;

    /** Hostname or IP address the HTTP server binds to. */
    private final String host;

    /** Core (minimum) thread count for the job executor pool. */
    private final int coreThreads;

    /** Maximum thread count for the job executor pool. */
    private final int maxThreads;

    /**
     * Time-to-live for completed/failed jobs before they are purged (minutes).
     * This controls how long <em>finished</em> jobs remain accessible via the
     * API — it is NOT the execution timeout.
     */
    private final int jobTtlMinutes;

    /**
     * Maximum wall-clock time (minutes) an algorithm child process is allowed
     * to run before it is forcibly killed.
     * <p>
     * Distinct from {@link #jobTtlMinutes}: the timeout fires while the job is
     * {@code RUNNING}; the TTL fires after the job has reached a terminal state.
     */
    private final int jobTimeoutMinutes;

    /** Maximum number of jobs that can be queued waiting for execution. */
    private final int maxQueueSize;

    /** Working directory where per-job input/output files are stored. */
    private final String workDir;

    /** Maximum allowed size (in MB) for an uploaded input file. */
    private final int maxInputSizeMb;

    /**
     * Optional API key for request authentication.
     * An empty string means API-key security is disabled.
     */
    private final String apiKey;

    /** JUL logging level name (e.g. {@code "INFO"}, {@code "FINE"}). */
    private final String logLevel;

    /** Path to the rotating log file; empty string disables file logging. */
    private final String logFile;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Private constructor — use {@link #load(String)} to obtain an instance.
     *
     * @param p {@link Properties} object (possibly empty) to read values from
     */
    private ServerConfig(Properties p) {
        port              = clamp(getInt(p, "server.port",            DEFAULT_PORT),
                                  MIN_PORT,            MAX_PORT,
                                  "server.port",        DEFAULT_PORT);
        host              = p.getProperty("server.host", DEFAULT_HOST);
        coreThreads       = clamp(getInt(p, "executor.coreThreads",   DEFAULT_CORE_THREADS),
                                  MIN_THREADS,          MAX_THREADS_LIMIT,
                                  "executor.coreThreads", DEFAULT_CORE_THREADS);

        // Parse maxThreads, then ensure it is >= coreThreads
        int rawMax        = clamp(getInt(p, "executor.maxThreads",    DEFAULT_MAX_THREADS),
                                  MIN_THREADS,          MAX_THREADS_LIMIT,
                                  "executor.maxThreads", DEFAULT_MAX_THREADS);
        maxThreads        = Math.max(rawMax, coreThreads);
        if (rawMax < coreThreads) {
            log.warning("executor.maxThreads (" + rawMax
                    + ") is less than executor.coreThreads (" + coreThreads
                    + ") — clamping maxThreads to " + maxThreads + ".");
        }

        jobTtlMinutes     = clamp(getInt(p, "job.ttlMinutes",         DEFAULT_TTL_MINUTES),
                                  MIN_TTL_MINUTES,      MAX_TTL_MINUTES,
                                  "job.ttlMinutes",     DEFAULT_TTL_MINUTES);
        jobTimeoutMinutes = clamp(getInt(p, "job.timeoutMinutes",     DEFAULT_JOB_TIMEOUT_MINUTES),
                                  MIN_TIMEOUT_MINUTES,  MAX_TIMEOUT_MINUTES,
                                  "job.timeoutMinutes", DEFAULT_JOB_TIMEOUT_MINUTES);
        maxQueueSize      = clamp(getInt(p, "job.maxQueueSize",       DEFAULT_QUEUE_SIZE),
                                  MIN_QUEUE_SIZE,       MAX_QUEUE_SIZE,
                                  "job.maxQueueSize",   DEFAULT_QUEUE_SIZE);
        workDir           = p.getProperty("work.dir",          DEFAULT_WORK_DIR);
        maxInputSizeMb    = clamp(getInt(p, "input.maxSizeMb",        DEFAULT_MAX_MB),
                                  MIN_MAX_MB,           MAX_MAX_MB,
                                  "input.maxSizeMb",    DEFAULT_MAX_MB);
        apiKey            = p.getProperty("security.apiKey", "").trim();
        logLevel          = p.getProperty("log.level",        DEFAULT_LOG_LEVEL);
        logFile           = p.getProperty("log.file",         DEFAULT_LOG_FILE);
    }

    // ── Factory method ─────────────────────────────────────────────────────

    /**
     * Load server configuration from a {@code .properties} file.
     *
     * <p>
     * If the specified file does not exist, or cannot be read, a warning is
     * logged and a configuration object containing all default values is
     * returned. The server will therefore always be able to start.
     *
     * @param path path to the {@code .properties} file
     *             (absolute or relative to the working directory)
     * @return a fully populated, immutable {@link ServerConfig}
     */
    public static ServerConfig load(String path) {
        Properties p = new Properties();
        File file = new File(path);

        if (file.exists()) {
            try (InputStream is = new FileInputStream(file)) {
                p.load(is);
                log.info("Configuration loaded from: " + file.getAbsolutePath());
            } catch (Exception e) {
                log.warning("Could not read config file '" + path
                        + "': " + e.getMessage() + " — using defaults.");
            }
        } else {
            log.warning("Config file '" + path
                    + "' not found — using built-in defaults.");
        }

        return new ServerConfig(p);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Read an integer property, falling back to {@code defaultValue} when the
     * property is absent or its value is not a valid integer.
     *
     * @param p            the properties source
     * @param key          property key to look up
     * @param defaultValue value to use when the key is missing or unparseable
     * @return the parsed integer value, or {@code defaultValue}
     */
    private static int getInt(Properties p, String key, int defaultValue) {
        String value = p.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warning("Invalid integer for property '" + key
                    + "' (value='" + value + "'). Using default: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Clamp {@code value} to [{@code min}, {@code max}].
     * <p>
     * If the value is out of range a warning is logged and the default is
     * returned (not the clamped boundary), so that config problems are
     * obvious rather than silently producing unexpected behaviour.
     *
     * @param value        the value to check
     * @param min          inclusive lower bound
     * @param max          inclusive upper bound
     * @param key          property key (for the warning message)
     * @param defaultValue value to substitute when out of range
     * @return {@code value} if in range, otherwise {@code defaultValue}
     */
    private static int clamp(int value, int min, int max,
                              String key, int defaultValue) {
        if (value < min || value > max) {
            log.warning("Value " + value + " for property '" + key
                    + "' is outside the allowed range [" + min + ", " + max
                    + "]. Using default: " + defaultValue + ".");
            return defaultValue;
        }
        return value;
    }

    // ── Getters ────────────────────────────────────────────────────────────

    /**
     * Returns the TCP port the HTTP server will bind to.
     *
     * @return port number (default: {@value #DEFAULT_PORT})
     */
    public int getPort() { return port; }

    /**
     * Returns the hostname or IP address the HTTP server will bind to.
     *
     * @return bind address (default: {@value #DEFAULT_HOST})
     */
    public String getHost() { return host; }

    /**
     * Returns the number of core (always-alive) threads in the executor pool.
     *
     * @return core thread count (default: {@value #DEFAULT_CORE_THREADS})
     */
    public int getCoreThreads() { return coreThreads; }

    /**
     * Returns the maximum number of threads the executor pool may create.
     *
     * @return max thread count (default: {@value #DEFAULT_MAX_THREADS})
     */
    public int getMaxThreads() { return maxThreads; }

    /**
     * Returns how long a completed or failed job is retained before the
     * cleaner purges it, in minutes.
     * <p>
     * <b>Note:</b> this is the post-completion retention time, not the
     * execution timeout. For the execution timeout see
     * {@link #getJobTimeoutMinutes()}.
     *
     * @return job TTL in minutes (default: {@value #DEFAULT_TTL_MINUTES})
     */
    public int getJobTtlMinutes() { return jobTtlMinutes; }

    /**
     * Returns the maximum time (in minutes) that an algorithm child process is
     * allowed to run before it is forcibly killed.
     * <p>
     * <b>Note:</b> this is the <em>execution</em> timeout, not the TTL.
     * Once a job finishes (successfully or via timeout), the TTL
     * ({@link #getJobTtlMinutes()}) controls how long the result is retained.
     *
     * @return job execution timeout in minutes
     *         (default: {@value #DEFAULT_JOB_TIMEOUT_MINUTES})
     */
    public int getJobTimeoutMinutes() { return jobTimeoutMinutes; }

    /**
     * Returns the maximum number of jobs that may wait in the execution queue
     * before new submissions are rejected.
     *
     * @return max queue size (default: {@value #DEFAULT_QUEUE_SIZE})
     */
    public int getMaxQueueSize() { return maxQueueSize; }

    /**
     * Returns the working directory used to store per-job input/output files.
     *
     * @return working directory path (default: {@value #DEFAULT_WORK_DIR})
     */
    public String getWorkDir() { return workDir; }

    /**
     * Returns the maximum size (in megabytes) accepted for a single uploaded
     * input file. Requests exceeding this limit are rejected with HTTP 413.
     *
     * @return max upload size in MB (default: {@value #DEFAULT_MAX_MB})
     */
    public int getMaxInputSizeMb() { return maxInputSizeMb; }

    /**
     * Returns the API key used for request authentication, or an empty string
     * when API-key security is disabled.
     *
     * @return API key string, never {@code null}
     */
    public String getApiKey() { return apiKey; }

    /**
     * Returns {@code true} when API-key authentication is enabled
     * (i.e. the {@code security.apiKey} property was set to a non-empty value).
     *
     * @return {@code true} if an API key has been configured
     */
    public boolean isApiKeyEnabled() { return !apiKey.isEmpty(); }

    /**
     * Returns the JUL logging level name (e.g. {@code "INFO"}, {@code "FINE"}).
     *
     * @return log level string (default: {@value #DEFAULT_LOG_LEVEL})
     */
    public String getLogLevel() { return logLevel; }

    /**
     * Returns the path to the rotating log file, or an empty string when
     * file-based logging is disabled.
     *
     * @return log file path (default: {@value #DEFAULT_LOG_FILE})
     */
    public String getLogFile() { return logFile; }

    /**
     * Returns a human-readable summary of the active configuration.
     * Sensitive fields (API key) are masked.
     *
     * @return formatted string describing all configuration values
     */
    @Override
    public String toString() {
        return "ServerConfig{"
                + "port=" + port
                + ", host='" + host + '\''
                + ", coreThreads=" + coreThreads
                + ", maxThreads=" + maxThreads
                + ", jobTtlMinutes=" + jobTtlMinutes
                + ", jobTimeoutMinutes=" + jobTimeoutMinutes
                + ", maxQueueSize=" + maxQueueSize
                + ", workDir='" + workDir + '\''
                + ", maxInputSizeMb=" + maxInputSizeMb
                + ", apiKeyEnabled=" + isApiKeyEnabled()
                + ", logLevel='" + logLevel + '\''
                + ", logFile='" + logFile + '\''
                + '}';
    }
}