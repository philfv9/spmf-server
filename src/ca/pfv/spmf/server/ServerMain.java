package ca.pfv.spmf.server;
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
import ca.pfv.spmf.server.gui.ServerGui;
import ca.pfv.spmf.server.http.HttpServerBootstrap;
import ca.pfv.spmf.server.job.JobCleaner;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ServerLogger;

import javax.swing.*;
import java.util.logging.Logger;

/**
 * Entry point for SPMF-Server.
 *
 * Modes:
 *   java -cp "spmf-server.jar:spmf.jar" ca.pfv.spmfserver.ServerMain
 *            → launches the Swing GUI (default when no args)
 *
 *   java -cp "spmf-server.jar:spmf.jar" ca.pfv.spmfserver.ServerMain --headless [config.properties]
 *            → headless command-line mode
 *
 *   java -cp "spmf-server.jar:spmf.jar" ca.pfv.spmfserver.ServerMain [config.properties]
 *            → if a .properties file is the first arg, also headless
 */
public class ServerMain {

    private static final Logger log = ServerLogger.get(ServerMain.class);

    /** Shared server state — populated by startServer(), used by both GUI and CLI. */
    public static volatile HttpServerBootstrap httpServer;
    public static volatile JobManager          jobManager;
    public static volatile JobCleaner          jobCleaner;
    public static volatile SpmfCatalogue       catalogue;
    public static volatile boolean             running = false;

    // ── Entry point ────────────────────────────────────────────────────────

    public static void main(String[] args) {

        // ── FIX: JVM-wide uncaught-exception handler ───────────────────────
        // If any thread (HTTP dispatcher, Java2D Disposer, etc.) throws an
        // OutOfMemoryError or any other uncaught Throwable, log it clearly
        // instead of letting the JVM silently print to stderr and potentially
        // leave the server in a broken state.
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (throwable instanceof OutOfMemoryError) {
                // Write directly to stderr — the logging system may itself
                // be unable to allocate when we are truly OOM.
                System.err.println("[SPMF-Server] FATAL OutOfMemoryError on "
                        + "thread '" + thread.getName() + "': "
                        + throwable.getMessage());
                System.err.println("[SPMF-Server] Increase JVM heap with "
                        + "-Xmx (e.g. -Xmx2g) and restart.");
                // Do NOT call System.exit() here — other threads and the
                // HTTP server can keep serving other jobs.
            } else {
                // For non-OOM uncaught exceptions, use the normal logger.
                log.severe("Uncaught exception on thread '"
                        + thread.getName() + "': " + throwable);
            }
        });

        boolean headless   = false;
        String  configPath = "spmf-server.properties";

        for (String arg : args) {
            if ("--headless".equalsIgnoreCase(arg)) {
                headless = true;
            } else if (!arg.startsWith("--")) {
                configPath = arg;
            }
        }

        System.out.println(System.getProperty("java.class.path"));

        if (!headless && !java.awt.GraphicsEnvironment.isHeadless()) {
            final String cfg = configPath;
            SwingUtilities.invokeLater(() -> new ServerGui(cfg).show());
        } else {
            runHeadless(configPath);
        }
    }

    // ── Headless mode ──────────────────────────────────────────────────────

    private static void runHeadless(String configPath) {
        ServerConfig config = ServerConfig.load(configPath);
        ServerLogger.configure(config.getLogLevel(), config.getLogFile());

        log.info("SPMF-Server v1.0.0 — headless mode");
        log.info("Config: " + configPath);

        try {
            startServer(config);
        } catch (Exception e) {
            log.severe("Failed to start server: " + e.getMessage());
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal — stopping...");
            stopServer();
            log.info("Stopped.");
        }, "shutdown-hook"));

        log.info("Server ready on port " + config.getPort() +
                 ". Press Ctrl+C to stop.");

        try { Thread.currentThread().join(); }
        catch (InterruptedException ignored) {}
    }

    // ── Shared start / stop ────────────────────────────────────────────────

    public static synchronized void startServer(ServerConfig config)
            throws Exception {
        if (running) throw new IllegalStateException(
                "Server is already running.");

        catalogue  = SpmfCatalogue.getInstance();
        jobManager = new JobManager(config, catalogue);
        jobCleaner = new JobCleaner(jobManager, config);
        jobCleaner.start();

        httpServer = new HttpServerBootstrap(config, jobManager, catalogue);
        httpServer.start();

        running = true;
        log.info("Server started on port " + config.getPort());
    }

    public static synchronized void stopServer() {
        if (!running) return;
        if (httpServer != null) httpServer.stop();
        if (jobManager != null) jobManager.shutdown();
        if (jobCleaner != null) jobCleaner.stop();
        running = false;
        log.info("Server stopped.");
    }
}