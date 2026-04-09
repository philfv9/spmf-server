package ca.pfv.spmf.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

import ca.pfv.spmf.server.util.ServerLogger;

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
 * Reads and exposes all server configuration.
 * Falls back to sensible defaults when a property is absent.
 * Zero external dependencies.
 */
public final class ServerConfig {

    private static final Logger log = ServerLogger.get(ServerConfig.class);

    // ── defaults ───────────────────────────────────────────────────────────
    private static final int    D_PORT         = 8585;
    private static final String D_HOST         = "0.0.0.0";
    private static final int    D_CORE_THREADS = 4;
    private static final int    D_MAX_THREADS  = 8;
    private static final int    D_TTL          = 30;
    private static final int    D_QUEUE        = 100;
    private static final String D_WORK_DIR     = "./spmf-work";
    private static final int    D_MAX_MB       = 50;
    private static final String D_LOG_LEVEL    = "INFO";
    private static final String D_LOG_FILE     = "./logs/spmf-server.log";

    // ── fields ─────────────────────────────────────────────────────────────
    private final int    port;
    private final String host;
    private final int    coreThreads;
    private final int    maxThreads;
    private final int    jobTtlMinutes;
    private final int    maxQueueSize;
    private final String workDir;
    private final int    maxInputSizeMb;
    private final String apiKey;
    private final String logLevel;
    private final String logFile;

    private ServerConfig(Properties p) {
        port           = getInt(p, "server.port",          D_PORT);
        host           = p.getProperty("server.host",      D_HOST);
        coreThreads    = getInt(p, "executor.coreThreads", D_CORE_THREADS);
        maxThreads     = getInt(p, "executor.maxThreads",  D_MAX_THREADS);
        jobTtlMinutes  = getInt(p, "job.ttlMinutes",       D_TTL);
        maxQueueSize   = getInt(p, "job.maxQueueSize",     D_QUEUE);
        workDir        = p.getProperty("work.dir",         D_WORK_DIR);
        maxInputSizeMb = getInt(p, "input.maxSizeMb",      D_MAX_MB);
        apiKey         = p.getProperty("security.apiKey",  "").trim();
        logLevel       = p.getProperty("log.level",        D_LOG_LEVEL);
        logFile        = p.getProperty("log.file",         D_LOG_FILE);
    }

    /**
     * Load configuration from a .properties file.
     * If the file is missing, all defaults are used.
     */
    public static ServerConfig load(String path) {
        Properties p = new Properties();
        File f = new File(path);
        if (f.exists()) {
            try (InputStream is = new FileInputStream(f)) {
                p.load(is);
                log.info("Configuration loaded from: " + f.getAbsolutePath());
            } catch (Exception e) {
                log.warning("Error reading config file '" + path +
                            "': " + e.getMessage() + " — using defaults.");
            }
        } else {
            log.warning("Config file '" + path + "' not found — using defaults.");
        }
        return new ServerConfig(p);
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private static int getInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try   { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) {
            log.warning("Bad integer for '" + key + "': '" + v +
                        "'. Using default " + def + ".");
            return def;
        }
    }

    // ── getters ────────────────────────────────────────────────────────────
    public int    getPort()           { return port; }
    public String getHost()           { return host; }
    public int    getCoreThreads()    { return coreThreads; }
    public int    getMaxThreads()     { return maxThreads; }
    public int    getJobTtlMinutes()  { return jobTtlMinutes; }
    public int    getMaxQueueSize()   { return maxQueueSize; }
    public String getWorkDir()        { return workDir; }
    public int    getMaxInputSizeMb() { return maxInputSizeMb; }
    public String getApiKey()         { return apiKey; }
    public boolean isApiKeyEnabled()  { return !apiKey.isEmpty(); }
    public String getLogLevel()       { return logLevel; }
    public String getLogFile()        { return logFile; }
}