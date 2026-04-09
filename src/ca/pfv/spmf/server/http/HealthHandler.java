package ca.pfv.spmf.server.http;

import com.sun.net.httpserver.HttpExchange;

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.SimpleJson;
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
 * GET /api/health  → liveness + stats
 * GET /api/info    → configuration summary
 */
public final class HealthHandler extends BaseHandler {

    private final ServerConfig  config;
    private final JobManager    jobManager;
    private final SpmfCatalogue catalogue;
    private final long          startedAt = System.currentTimeMillis();

    public HealthHandler(ServerConfig config,
                         JobManager jobManager,
                         SpmfCatalogue catalogue,
                         ApiKeyFilter apiKeyFilter) {
        super(apiKeyFilter);
        this.config     = config;
        this.jobManager = jobManager;
        this.catalogue  = catalogue;
    }

    @Override
    protected void doHandle(HttpExchange ex) throws Exception {
        if (!"GET".equals(method(ex))) {
            sendJson(ex, 405, SimpleJson.error("Method not allowed", 405));
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/info")) {
            handleInfo(ex);
        } else {
            handleHealth(ex);
        }
    }

    private void handleHealth(HttpExchange ex) throws Exception {
        long uptimeSec = (System.currentTimeMillis() - startedAt) / 1000L;
        sendJson(ex, 200, SimpleJson.object()
                .put("status",               "UP")
                .put("version",              "1.0.0")
                .put("spmfAlgorithmsLoaded", catalogue.size())
                .put("uptimeSeconds",        uptimeSec)
                .put("activeJobs",           jobManager.activeJobCount())
                .put("queuedJobs",           jobManager.queuedJobCount())
                .put("totalJobsInRegistry",  jobManager.totalJobCount())
                .build());
    }

    private void handleInfo(HttpExchange ex) throws Exception {
        sendJson(ex, 200, SimpleJson.object()
                .put("version",        "1.0.0")
                .put("port",           config.getPort())
                .put("host",           config.getHost())
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