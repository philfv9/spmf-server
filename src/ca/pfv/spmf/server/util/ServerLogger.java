package ca.pfv.spmf.server.util;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.*;
/*
 *  Copyright (c) 2026 Philippe Fournier-Viger
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF.  If not, see <http://www.gnu.org/licenses/>.
 */
/**
 * Central logging configurator using java.util.logging (JUL) only.
 *
 * <p>Call {@link #configure(String, String)} once at startup.
 * All classes then obtain loggers via {@link #get(Class)}.
 */
public final class ServerLogger {

    private static final String ROOT_LOGGER_NAME = "ca.pfv.spmfserver";

    private ServerLogger() {}

    /**
     * Configure JUL: set level, attach console handler, attach rolling file handler.
     *
     * @param levelName  e.g. "INFO", "FINE", "WARNING"
     * @param logFilePath path to log file; parent dirs are created automatically
     */
    public static void configure(String levelName, String logFilePath) {
        Level level;
        try {
            level = Level.parse(levelName.toUpperCase());
        } catch (IllegalArgumentException e) {
            level = Level.INFO;
        }

        // Remove any handlers already attached to the root JUL logger
        Logger rootJul = Logger.getLogger("");
        for (Handler h : rootJul.getHandlers()) rootJul.removeHandler(h);

        // Our package-level logger
        Logger pkgLogger = Logger.getLogger(ROOT_LOGGER_NAME);
        pkgLogger.setUseParentHandlers(false);
        pkgLogger.setLevel(level);

        Formatter fmt = new SingleLineFormatter();

        // Console handler
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(level);
        ch.setFormatter(fmt);
        pkgLogger.addHandler(ch);

        // File handler (if path is non-empty)
        if (logFilePath != null && !logFilePath.isBlank()) {
            try {
                File logFile = new File(logFilePath);
                if (logFile.getParentFile() != null) {
                    logFile.getParentFile().mkdirs();
                }
                // %g = generation number for rotation, %u = unique number
                FileHandler fh = new FileHandler(
                        logFile.getAbsolutePath(), 10_000_000 /*10 MB*/, 5, true);
                fh.setLevel(level);
                fh.setFormatter(fmt);
                pkgLogger.addHandler(fh);
            } catch (IOException e) {
                pkgLogger.warning("Could not open log file '" + logFilePath +
                                  "': " + e.getMessage());
            }
        }
    }

    /** Obtain a JUL logger for the given class. */
    public static Logger get(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }

    // ── Custom single-line formatter ───────────────────────────────────────

    private static final class SingleLineFormatter extends Formatter {
        private final SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        @Override
        public String format(LogRecord r) {
            String ts     = sdf.format(new Date(r.getMillis()));
            String level  = String.format("%-7s", r.getLevel().getName());
            String logger = r.getLoggerName();
            // Shorten logger name: keep only the simple class name
            int dot = logger.lastIndexOf('.');
            if (dot >= 0) logger = logger.substring(dot + 1);
            logger = String.format("%-25s", logger);

            StringBuilder sb = new StringBuilder();
            sb.append(ts).append(" [").append(level).append("] ")
              .append(logger).append(" - ")
              .append(formatMessage(r)).append('\n');

            if (r.getThrown() != null) {
                // Append first few lines of stack trace
                for (StackTraceElement e : r.getThrown().getStackTrace()) {
                    sb.append("    at ").append(e).append('\n');
                }
            }
            return sb.toString();
        }
    }
}