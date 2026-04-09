package ca.pfv.spmf.server.job;

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.util.ServerLogger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.*;
import java.util.logging.Logger;
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
public final class JobCleaner {

    private static final Logger log = ServerLogger.get(JobCleaner.class);

    private final JobManager            jobManager;
    private final ServerConfig          config;
    private final ScheduledExecutorService scheduler;

    public JobCleaner(JobManager jobManager, ServerConfig config) {
        this.jobManager = jobManager;
        this.config     = config;
        this.scheduler  = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-cleaner");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::clean, 1, 1, TimeUnit.MINUTES);
        log.info("JobCleaner started (TTL=" + config.getJobTtlMinutes() + " min)");
    }

    public void stop() { scheduler.shutdownNow(); }

    private void clean() {
        Instant cutoff = Instant.now()
                .minus(config.getJobTtlMinutes(), ChronoUnit.MINUTES);
        int removed = 0;
        for (Job job : jobManager.getAllJobs()) {
            if (job.isTerminal()
                    && job.getFinishedAt() != null
                    && job.getFinishedAt().isBefore(cutoff)) {
                jobManager.deleteJob(job.getJobIdString());
                removed++;
            }
        }
        if (removed > 0)
            log.info("JobCleaner evicted " + removed + " expired job(s)");
    }
}