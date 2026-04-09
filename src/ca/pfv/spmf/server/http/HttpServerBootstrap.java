package ca.pfv.spmf.server.http;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ApiKeyFilter;
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
public final class HttpServerBootstrap {

    private static final Logger log = ServerLogger.get(HttpServerBootstrap.class);

    private final ServerConfig  config;
    private final JobManager    jobManager;
    private final SpmfCatalogue catalogue;
    private HttpServer          httpServer;

    public HttpServerBootstrap(ServerConfig config,
                               JobManager jobManager,
                               SpmfCatalogue catalogue) {
        this.config     = config;
        this.jobManager = jobManager;
        this.catalogue  = catalogue;
    }

    public void start() throws Exception {
        InetSocketAddress addr =
                new InetSocketAddress(config.getHost(), config.getPort());
        httpServer = HttpServer.create(addr, 0);

        ApiKeyFilter filter = new ApiKeyFilter(config);

        httpServer.createContext("/api/algorithms",
                new AlgorithmHandler(config, catalogue, filter));
        httpServer.createContext("/api/run",
                new RunHandler(config, jobManager, catalogue, filter));
        httpServer.createContext("/api/jobs",
                new JobHandler(config, jobManager, filter));
        httpServer.createContext("/api/health",
                new HealthHandler(config, jobManager, catalogue, filter));
        httpServer.createContext("/api/info",
                new HealthHandler(config, jobManager, catalogue, filter));

        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("http-" + t.getId());
            t.setDaemon(true);
            return t;
        }));

        httpServer.start();
        log.info("HTTP server listening on " + config.getHost() +
                 ":" + config.getPort());
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(5);
            log.info("HTTP server stopped.");
        }
    }
}