package ca.pfv.spmf.server;

import java.util.logging.Logger;

import javax.swing.SwingUtilities;

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

import ca.pfv.spmf.server.gui.ServerGui;
import ca.pfv.spmf.server.http.HttpServerBootstrap;
import ca.pfv.spmf.server.job.JobCleaner;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ServerLogger;

/**
 * Entry point for the SPMF-Server application.
 * <p>
 * This class bootstraps the server in either GUI or headless (CLI) mode,
 * manages shared server state, and provides synchronized start/stop lifecycle
 * methods used by both modes.
 * <p>
 * <b>Usage modes:</b>
 * <pre>
 *   # GUI mode (default when no arguments are given):
 *   java -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain
 *
 *   # Headless CLI mode:
 *   java -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain --headless [config.properties]
 *
 *   # Headless CLI mode (config file as first argument implies headless):
 *   java -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain config.properties
 * </pre>
 *
 * @author Philippe Fournier-Viger
 * @see ServerConfig
 * @see HttpServerBootstrap
 * @see JobManager
 * @see JobCleaner
 * @see SpmfCatalogue
 */
public class ServerMain {

    /** Logger for this class. */
    private static final Logger log = ServerLogger.get(ServerMain.class);

    /** Default configuration file name. */
    private static final String DEFAULT_CONFIG = "spmf-server.properties";

    /** Server version string. */
    private static final String VERSION = "1.0.0";

    // ── Shared server state ────────────────────────────────────────────────
    // These fields are populated by startServer() and consumed by both
    // the GUI and CLI paths. Declared volatile so that visibility across
    // threads is guaranteed without requiring full synchronization on every read.

    /** The HTTP server bootstrap that owns the listening socket. */
    public static volatile HttpServerBootstrap httpServer;

    /** Manages the lifecycle of submitted algorithm jobs. */
    public static volatile JobManager jobManager;

    /** Background thread that evicts expired jobs from the job store. */
    public static volatile JobCleaner jobCleaner;

    /** Read-only catalogue of all available SPMF algorithms. */
    public static volatile SpmfCatalogue catalogue;

    /** {@code true} while the server is accepting requests. */
    public static volatile boolean running = false;

    // ── Entry point ────────────────────────────────────────────────────────

    /**
     * Application entry point.
     * <p>
     * Parses command-line arguments to determine the run mode, installs a
     * JVM-wide uncaught-exception handler, then delegates to either the
     * Swing GUI or the headless CLI path.
     *
     * @param args command-line arguments:
     *             {@code --headless} to force CLI mode;
     *             an optional path to a {@code .properties} config file
     */
    public static void main(String[] args) {

        // Install a JVM-wide uncaught-exception handler so that any thread
        // (HTTP dispatcher, cleaner, Java2D Disposer, etc.) that throws an
        // unhandled Throwable is logged clearly rather than silently dying.
        installUncaughtExceptionHandler();

        // Parse arguments
        boolean headless = false;
        String configPath = DEFAULT_CONFIG;

        for (String arg : args) {
            if ("--headless".equalsIgnoreCase(arg)) {
                headless = true;
            } else if (!arg.startsWith("--")) {
                // A positional argument is treated as the config file path.
                configPath = arg;
            }
        }

        // Log the classpath for diagnostic purposes during startup
        log.fine("JVM classpath: " + System.getProperty("java.class.path"));

        if (!headless && !java.awt.GraphicsEnvironment.isHeadless()) {
            // Launch the Swing GUI on the Event Dispatch Thread
            final String cfg = configPath;
            SwingUtilities.invokeLater(() -> new ServerGui(cfg).show());
        } else {
            runHeadless(configPath);
        }
    }

    // ── Headless (CLI) mode ────────────────────────────────────────────────

    /**
     * Runs the server in headless (command-line) mode.
     * <p>
     * Loads configuration, starts all subsystems, registers a JVM shutdown
     * hook to perform a clean stop, then blocks the main thread indefinitely
     * until a signal (e.g. {@code Ctrl+C}) is received.
     *
     * @param configPath path to the {@code .properties} configuration file
     */
    private static void runHeadless(String configPath) {

        // Load configuration (missing file → all defaults)
        ServerConfig config = ServerConfig.load(configPath);

        // Configure logging as early as possible so subsequent log calls
        // use the requested level and output file
        ServerLogger.configure(config.getLogLevel(), config.getLogFile());

        log.info("SPMF-Server v" + VERSION + " — headless mode");
        log.info("Configuration: " + configPath);

        // Start all server subsystems
        try {
            startServer(config);
        } catch (Exception e) {
            log.severe("Failed to start server: " + e.getMessage());
            System.exit(1);
        }

        // Register a shutdown hook so that Ctrl+C / SIGTERM triggers a
        // graceful stop instead of abruptly killing threads
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping server...");
            stopServer();
            log.info("Server stopped cleanly.");
        }, "spmf-shutdown-hook"));

        log.info("Server ready on port " + config.getPort()
                + ". Press Ctrl+C to stop.");

        // Block the main thread; the shutdown hook will unblock it on exit
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); // restore interrupt flag
        }
    }

    // ── Shared start / stop ────────────────────────────────────────────────

    /**
     * Start all server subsystems using the supplied configuration.
     * <p>
     * Initialises (in order):
     * <ol>
     *   <li>The {@link SpmfCatalogue} of available algorithms</li>
     *   <li>The {@link JobManager} that executes and tracks jobs</li>
     *   <li>The {@link JobCleaner} that purges expired jobs</li>
     *   <li>The {@link HttpServerBootstrap} that listens for HTTP requests</li>
     * </ol>
     * This method is {@code synchronized} so that concurrent calls from the
     * GUI and CLI paths cannot race.
     *
     * @param config server configuration to use
     * @throws IllegalStateException if the server is already running
     * @throws Exception             if any subsystem fails to start
     */
    public static synchronized void startServer(ServerConfig config)
            throws Exception {

        if (running) {
            throw new IllegalStateException("Server is already running.");
        }

        // 1. Algorithm catalogue (singleton — safe to call multiple times)
        catalogue = SpmfCatalogue.getInstance();

        // 2. Job manager — owns the thread-pool executor
        jobManager = new JobManager(config, catalogue);

        // 3. Job cleaner — runs as a daemon background thread
        jobCleaner = new JobCleaner(jobManager, config);
        jobCleaner.start();

        // 4. HTTP server — opens the listening socket last so that the
        //    job infrastructure is ready before any request arrives
        httpServer = new HttpServerBootstrap(config, jobManager, catalogue);
        httpServer.start();

        running = true;
        log.info("Server started on port " + config.getPort());
    }

    /**
     * Stop all server subsystems gracefully.
     * <p>
     * Subsystems are stopped in reverse order of creation.
     * Calling this method when the server is not running is a no-op.
     * This method is {@code synchronized} to match {@link #startServer}.
     */
    public static synchronized void stopServer() {

        if (!running) {
            return; // already stopped — nothing to do
        }

        // Stop in reverse startup order to avoid dangling references
        if (httpServer != null) httpServer.stop();
        if (jobManager  != null) jobManager.shutdown();
        if (jobCleaner  != null) jobCleaner.stop();

        running = false;
        log.info("Server stopped.");
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Install a JVM-wide uncaught-exception handler.
     * <p>
     * <ul>
     *   <li>For {@link OutOfMemoryError}: writes directly to {@code stderr}
     *       because the logging subsystem may itself be unable to allocate
     *       memory at that point. Does <em>not</em> call
     *       {@code System.exit()} so that other threads can continue.</li>
     *   <li>For all other {@link Throwable}s: delegates to the normal JUL
     *       logger at SEVERE level.</li>
     * </ul>
     */
    private static void installUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (throwable instanceof OutOfMemoryError) {
                // Avoid the logging system — it may itself need to allocate
                System.err.println("[SPMF-Server] FATAL OutOfMemoryError on"
                        + " thread '" + thread.getName() + "': "
                        + throwable.getMessage());
                System.err.println("[SPMF-Server] Increase the JVM heap with"
                        + " -Xmx (e.g. -Xmx2g) and restart the server.");
                // Do NOT exit — other threads / jobs may still be healthy
            } else {
                log.severe("Uncaught exception on thread '"
                        + thread.getName() + "': " + throwable);
            }
        });
    }
}