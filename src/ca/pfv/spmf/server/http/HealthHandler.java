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
import ca.pfv.spmf.server.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

/**
 * HTTP handler for server health and configuration endpoints.
 * <p>
 * Registered at two URL contexts by {@link HttpServerBootstrap}:
 * <ul>
 *   <li>{@code GET /api/health} — returns a liveness check with live
 *       runtime statistics (uptime, active/queued jobs, algorithm count).</li>
 *   <li>{@code GET /api/info}   — returns a summary of the active server
 *       configuration (port, thread counts, TTL, etc.).</li>
 * </ul>
 * Both endpoints accept only {@code GET} requests; any other HTTP method
 * returns HTTP 405.
 *
 * @author Philippe Fournier-Viger
 * @see HealthHandler#handleHealth(HttpExchange)
 * @see HealthHandler#handleInfo(HttpExchange)
 */
public final class HealthHandler extends BaseHandler {

    /** Current server version string included in every response. */
    private static final String SERVER_VERSION = "1.0.0";

    // ── Dependencies ───────────────────────────────────────────────────────

    /** Server configuration surfaced by the {@code /api/info} endpoint. */
    private final ServerConfig config;

    /** Job manager queried for live job counts in the health response. */
    private final JobManager jobManager;

    /** Algorithm catalogue queried for the loaded-algorithm count. */
    private final SpmfCatalogue catalogue;

    /**
     * System time (epoch ms) at which this handler was constructed.
     * Used to compute the server uptime returned by {@code /api/health}.
     */
    private final long startedAtMs = System.currentTimeMillis();

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct a {@code HealthHandler}.
     *
     * @param config       server configuration; must not be {@code null}
     * @param jobManager   job manager; must not be {@code null}
     * @param catalogue    SPMF algorithm catalogue; must not be {@code null}
     * @param apiKeyFilter API-key filter passed to {@link BaseHandler};
     *                     must not be {@code null}
     */
    public HealthHandler(ServerConfig config,
                         JobManager jobManager,
                         SpmfCatalogue catalogue,
                         ApiKeyFilter apiKeyFilter) {
        super(apiKeyFilter);
        this.config     = config;
        this.jobManager = jobManager;
        this.catalogue  = catalogue;
    }

    // ── BaseHandler implementation ─────────────────────────────────────────

    /**
     * Route the request to either {@link #handleHealth(HttpExchange)} or
     * {@link #handleInfo(HttpExchange)} based on the URI path suffix.
     * Returns HTTP 405 for non-{@code GET} methods.
     *
     * @param ex the HTTP exchange to handle
     * @throws Exception if an I/O error occurs while sending the response
     */
    @Override
    protected void doHandle(HttpExchange ex) throws Exception {
        if (!"GET".equals(method(ex))) {
            sendJson(ex, 405, SimpleJson.error("Method not allowed — use GET.", 405));
            return;
        }

        // Route based on the last path component
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/info")) {
            handleInfo(ex);
        } else {
            handleHealth(ex);
        }
    }

    // ── Private handler methods ────────────────────────────────────────────

    /**
     * Handle {@code GET /api/health}.
     * <p>
     * Returns HTTP 200 with a JSON object containing:
     * <ul>
     *   <li>{@code status}               — always {@code "UP"}</li>
     *   <li>{@code version}              — server version string</li>
     *   <li>{@code spmfAlgorithmsLoaded} — number of algorithms in the catalogue</li>
     *   <li>{@code uptimeSeconds}        — seconds since the handler was created</li>
     *   <li>{@code activeJobs}           — jobs currently executing</li>
     *   <li>{@code queuedJobs}           — jobs waiting in the submission queue</li>
     *   <li>{@code totalJobsInRegistry}  — all tracked jobs (including terminal)</li>
     * </ul>
     *
     * @param ex the HTTP exchange to respond to
     * @throws Exception if an I/O error occurs
     */
    private void handleHealth(HttpExchange ex) throws Exception {
        long uptimeSeconds = (System.currentTimeMillis() - startedAtMs) / 1_000L;

        sendJson(ex, 200, SimpleJson.object()
                .put("status",               "UP")
                .put("version",              SERVER_VERSION)
                .put("spmfAlgorithmsLoaded", catalogue.size())
                .put("uptimeSeconds",        uptimeSeconds)
                .put("activeJobs",           jobManager.activeJobCount())
                .put("queuedJobs",           jobManager.queuedJobCount())
                .put("totalJobsInRegistry",  jobManager.totalJobCount())
                .build());
    }

    /**
     * Handle {@code GET /api/info}.
     * <p>
     * Returns HTTP 200 with a JSON object that mirrors the active
     * {@link ServerConfig} values.  Sensitive fields (API key value) are
     * never included — only the {@code apiKeyEnabled} flag is surfaced.
     *
     * @param ex the HTTP exchange to respond to
     * @throws Exception if an I/O error occurs
     */
    private void handleInfo(HttpExchange ex) throws Exception {
        sendJson(ex, 200, SimpleJson.object()
                .put("version",        SERVER_VERSION)
                .put("host",           config.getHost())
                .put("port",           config.getPort())
                .put("coreThreads",    config.getCoreThreads())
                .put("maxThreads",     config.getMaxThreads())
                .put("jobTtlMinutes",  config.getJobTtlMinutes())
                .put("maxQueueSize",   config.getMaxQueueSize())
                .put("workDir",        config.getWorkDir())
                .put("maxInputSizeMb", config.getMaxInputSizeMb())
                .put("apiKeyEnabled",  config.isApiKeyEnabled())
                .put("logLevel",       config.getLogLevel())
                .build());
    }
}