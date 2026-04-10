package ca.pfv.spmf.server.http;

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

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.ServerLogger;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Bootstraps the JDK built-in HTTP server and registers all REST API
 * endpoint handlers.
 * <p>
 * This class owns the {@link HttpServer} instance and is responsible for
 * binding it to the configured host and port, wiring up all
 * {@link BaseHandler} subclasses to their URL contexts, and shutting the
 * server down gracefully on request.
 * <p>
 * <b>Registered endpoints:</b>
 * <pre>
 *   /api/algorithms  → {@link AlgorithmHandler}  (GET list, GET describe)
 *   /api/run         → {@link RunHandler}         (POST submit job)
 *   /api/jobs        → {@link JobHandler}         (GET list/status/result/console, DELETE)
 *   /api/health      → {@link HealthHandler}      (GET liveness + stats)
 *   /api/info        → {@link HealthHandler}      (GET configuration summary)
 * </pre>
 * All handlers share a single {@link ApiKeyFilter} instance so that the
 * API-key check is consistent across the entire API surface.
 * <p>
 * The HTTP server is backed by a cached thread pool; each incoming connection
 * is handled on a short-lived daemon thread named {@code "http-<id>"}.
 *
 * @author Philippe Fournier-Viger
 * @see BaseHandler
 * @see ServerConfig
 */
public final class HttpServerBootstrap {

    /** Logger for server start/stop lifecycle events. */
    private static final Logger log = ServerLogger.get(HttpServerBootstrap.class);

    /**
     * Number of seconds the HTTP server waits for in-flight requests to
     * complete when {@link #stop()} is called before forcibly closing all
     * connections.
     */
    private static final int STOP_DELAY_SECONDS = 5;

    // ── Dependencies ───────────────────────────────────────────────────────

    /** Server configuration (host, port, API key, etc.). */
    private final ServerConfig config;

    /** Job lifecycle manager passed to handlers that submit or query jobs. */
    private final JobManager jobManager;

    /** Read-only SPMF algorithm catalogue passed to algorithm-related handlers. */
    private final SpmfCatalogue catalogue;

    // ── Mutable state ──────────────────────────────────────────────────────

    /**
     * The running JDK HTTP server instance.
     * {@code null} before {@link #start()} is called or after {@link #stop()}.
     */
    private HttpServer httpServer;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct a bootstrap object.  The HTTP server is not started until
     * {@link #start()} is called.
     *
     * @param config     server configuration; must not be {@code null}
     * @param jobManager job manager to pass to request handlers; must not be
     *                   {@code null}
     * @param catalogue  SPMF algorithm catalogue to pass to request handlers;
     *                   must not be {@code null}
     */
    public HttpServerBootstrap(ServerConfig config,
                               JobManager jobManager,
                               SpmfCatalogue catalogue) {
        this.config     = config;
        this.jobManager = jobManager;
        this.catalogue  = catalogue;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Create and start the HTTP server.
     * <p>
     * Binds to the address and port defined in {@link ServerConfig}, registers
     * all endpoint contexts, and begins accepting connections.
     *
     * @throws Exception if the socket cannot be bound or the server fails to
     *                   start
     */
    public void start() throws Exception {

        InetSocketAddress address =
                new InetSocketAddress(config.getHost(), config.getPort());
        httpServer = HttpServer.create(address, 0);

        // Shared API-key filter — applied by every handler's BaseHandler.handle()
        ApiKeyFilter apiKeyFilter = new ApiKeyFilter(config);

        // Register all endpoint contexts
        httpServer.createContext("/api/algorithms",
                new AlgorithmHandler(config, catalogue, apiKeyFilter));
        httpServer.createContext("/api/run",
                new RunHandler(config, jobManager, catalogue, apiKeyFilter));
        httpServer.createContext("/api/jobs",
                new JobHandler(config, jobManager, apiKeyFilter));
        httpServer.createContext("/api/health",
                new HealthHandler(config, jobManager, catalogue, apiKeyFilter));
        httpServer.createContext("/api/info",
                new HealthHandler(config, jobManager, catalogue, apiKeyFilter));

        // Use a cached thread pool so that the number of concurrent HTTP
        // handler threads scales with request load. All threads are daemons
        // so they do not prevent JVM shutdown.
        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("http-" + t.getId());
            t.setDaemon(true);
            return t;
        }));

        httpServer.start();
        log.info("HTTP server listening on "
                + config.getHost() + ":" + config.getPort());
    }

    /**
     * Stop the HTTP server gracefully.
     * <p>
     * Waits up to {@value #STOP_DELAY_SECONDS} seconds for in-flight requests
     * to complete before forcibly closing all connections.  Calling this method
     * when the server has not been started is a no-op.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(STOP_DELAY_SECONDS);
            log.info("HTTP server stopped.");
        }
    }
}