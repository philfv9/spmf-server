package ca.pfv.spmf.server.util;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 *
 * This file is part of the SPMF SERVER
 * (http://www.philippe-fournier-viger.com/spmf).
 *
 * SPMF is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SPMF is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF. If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Central logging configurator for the SPMF-Server.
 * <p>
 * Uses the Java standard {@code java.util.logging} (JUL) framework exclusively
 * — no external logging libraries are required.
 * <p>
 * <b>Typical usage:</b>
 * <ol>
 *   <li>Call {@link #configure(String, String)} <em>once</em> at application
 *       startup (before any other component logs).</li>
 *   <li>Obtain per-class loggers via {@link #get(Class)} in each class that
 *       needs to log.</li>
 * </ol>
 *
 * <b>Features:</b>
 * <ul>
 *   <li>Single-line, human-readable log format with timestamp, level, and
 *       short class name.</li>
 *   <li>Simultaneous console and rotating file output.</li>
 *   <li>File handler limited to 10 MB per file, with up to 5 rotating
 *       generations, appending to any existing log on restart.</li>
 * </ul>
 *
 * @author Philippe Fournier-Viger
 */
public final class ServerLogger {

    /**
     * Root logger name for the entire SPMF-Server package hierarchy.
     * All loggers obtained via {@link #get(Class)} are children of this
     * logger and inherit its handlers and level.
     */
    private static final String ROOT_LOGGER_NAME = "ca.pfv.spmfserver";

    /** Maximum size (bytes) of a single log file before rotation (10 MB). */
    private static final int LOG_FILE_MAX_BYTES = 10_000_000;

    /** Number of rotating log file generations to keep. */
    private static final int LOG_FILE_COUNT = 5;

    /** Prevent instantiation — all methods are static. */
    private ServerLogger() {}

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Configure JUL for the SPMF-Server package.
     * <p>
     * This method:
     * <ol>
     *   <li>Removes all handlers from the root JUL logger to suppress
     *       duplicate output.</li>
     *   <li>Creates a package-level logger ({@value #ROOT_LOGGER_NAME}) with
     *       the requested level.</li>
     *   <li>Attaches a {@link ConsoleHandler} at the requested level.</li>
     *   <li>Optionally attaches a rotating {@link FileHandler} if
     *       {@code logFilePath} is non-empty; parent directories are created
     *       automatically.</li>
     * </ol>
     * Calling this method more than once has no harmful effect, but the
     * handlers will accumulate — it is intended to be called once at startup.
     *
     * @param levelName   JUL level name (e.g. {@code "INFO"}, {@code "FINE"},
     *                    {@code "WARNING"}); invalid names default to
     *                    {@link Level#INFO}
     * @param logFilePath file-system path for the log file; parent directories
     *                    are created if absent; pass an empty string or
     *                    {@code null} to disable file logging
     */
    public static void configure(String levelName, String logFilePath) {

        // Resolve the requested level, defaulting to INFO on bad input
        Level level;
        try {
            level = Level.parse(levelName.toUpperCase());
        } catch (IllegalArgumentException e) {
            level = Level.INFO;
        }

        // Remove all handlers from the root JUL logger to prevent the default
        // ConsoleHandler from duplicating every log record
        Logger rootJul = Logger.getLogger("");
        for (Handler h : rootJul.getHandlers()) {
            rootJul.removeHandler(h);
        }

        // Obtain (or create) the package-level logger
        Logger pkgLogger = Logger.getLogger(ROOT_LOGGER_NAME);
        pkgLogger.setUseParentHandlers(false); // do not propagate to root
        pkgLogger.setLevel(level);

        Formatter fmt = new SingleLineFormatter();

        // Console handler — writes to System.err (JUL default)
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(level);
        consoleHandler.setFormatter(fmt);
        pkgLogger.addHandler(consoleHandler);

        // File handler — optional; skipped if path is blank
        if (logFilePath != null && !logFilePath.isBlank()) {
            try {
                File logFile = new File(logFilePath);

                // Create parent directories if they do not exist
                if (logFile.getParentFile() != null) {
                    logFile.getParentFile().mkdirs();
                }

                // Rotating file handler: 10 MB per file, 5 generations, append mode
                FileHandler fileHandler = new FileHandler(
                        logFile.getAbsolutePath(),
                        LOG_FILE_MAX_BYTES,
                        LOG_FILE_COUNT,
                        true /* append */);
                fileHandler.setLevel(level);
                fileHandler.setFormatter(fmt);
                pkgLogger.addHandler(fileHandler);

            } catch (IOException e) {
                // Log to console only — file logging is unavailable
                pkgLogger.warning("Could not open log file '"
                        + logFilePath + "': " + e.getMessage()
                        + " — file logging disabled.");
            }
        }
    }

    /**
     * Obtain a JUL {@link Logger} for the given class.
     * <p>
     * The returned logger is a child of the package-level logger configured
     * by {@link #configure(String, String)} and therefore inherits its level
     * and handlers automatically.
     *
     * @param clazz the class requesting the logger
     * @return a JUL {@link Logger} named after {@code clazz}
     */
    public static Logger get(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }

    // ── Custom formatter ───────────────────────────────────────────────────

    /**
     * Single-line log formatter that produces records in the format:
     * <pre>
     *   yyyy-MM-dd HH:mm:ss [LEVEL  ] ShortClassName            - message
     * </pre>
     * If the record carries a {@link Throwable}, its stack trace is appended
     * on subsequent lines (each prefixed with {@code "    at "}).
     */
    private static final class SingleLineFormatter extends Formatter {

        /**
         * Date/time formatter.
         * <p>
         * {@link SimpleDateFormat} is not thread-safe; access to this instance
         * is safe here because JUL calls {@link #format(LogRecord)} from a
         * single handler thread.
         */
        private final SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        /**
         * Format a single log record into a human-readable single-line string.
         *
         * @param r the log record to format
         * @return formatted log line (always ends with {@code '\n'})
         */
        @Override
        public String format(LogRecord r) {
            // Timestamp
            String timestamp = sdf.format(new Date(r.getMillis()));

            // Level — right-padded to 7 chars so columns align
            String level = String.format("%-7s", r.getLevel().getName());

            // Logger name — keep only the simple class name for brevity
            String loggerName = r.getLoggerName();
            int dot = loggerName.lastIndexOf('.');
            if (dot >= 0) loggerName = loggerName.substring(dot + 1);
            loggerName = String.format("%-25s", loggerName);

            StringBuilder sb = new StringBuilder();
            sb.append(timestamp)
              .append(" [").append(level).append("] ")
              .append(loggerName)
              .append(" - ")
              .append(formatMessage(r))
              .append('\n');

            // Append stack trace if a Throwable is attached
            if (r.getThrown() != null) {
                sb.append("  Exception: ")
                  .append(r.getThrown()).append('\n');
                for (StackTraceElement frame : r.getThrown().getStackTrace()) {
                    sb.append("    at ").append(frame).append('\n');
                }
            }

            return sb.toString();
        }
    }
}