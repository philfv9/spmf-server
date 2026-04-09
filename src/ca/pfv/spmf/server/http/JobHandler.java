package ca.pfv.spmf.server.http;

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.job.Job;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.job.JobStatus;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.SimpleJson;

import com.sun.net.httpserver.HttpExchange;
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
 * GET    /api/jobs                → list all jobs
 * GET    /api/jobs/{id}           → job status
 * GET    /api/jobs/{id}/result    → fetch result when DONE
 * DELETE /api/jobs/{id}           → delete / clean up job
 *
 * Extends BaseHandler which implements com.sun.net.httpserver.HttpHandler,
 * providing sendJson(), readBody(), method(), pathSegments() helpers and
 * the auth/CORS filter in handle().
 */
public final class JobHandler extends BaseHandler {

    private final JobManager jobManager;

    public JobHandler(ServerConfig config,
                      JobManager jobManager,
                      ApiKeyFilter apiKeyFilter) {
        super(apiKeyFilter);          // BaseHandler stores apiKeyFilter
        this.jobManager = jobManager;
    }

    // ── Entry point called by BaseHandler.handle() after auth check ────────

    @Override
    protected void doHandle(HttpExchange ex) throws Exception {

        String[] segments   = pathSegments(ex, "/api/jobs");
        String   httpMethod = method(ex);

        // ── /api/jobs ──────────────────────────────────────────────────────
        if (segments.length == 0) {
            if ("GET".equals(httpMethod)) {
                handleList(ex);
            } else {
                sendJson(ex, 405, SimpleJson.error("Method not allowed", 405));
            }
            return;
        }

        String jobId = segments[0];

        // ── /api/jobs/{id} ─────────────────────────────────────────────────
        if (segments.length == 1) {
            switch (httpMethod) {
                case "GET":
                    handleStatus(ex, jobId);
                    break;
                case "DELETE":
                    handleDelete(ex, jobId);
                    break;
                default:
                    sendJson(ex, 405, SimpleJson.error("Method not allowed", 405));
            }
            return;
        }

        // ── /api/jobs/{id}/result ──────────────────────────────────────────
        if (segments.length == 2 && "result".equals(segments[1])) {
            if ("GET".equals(httpMethod)) {
                handleResult(ex, jobId);
            } else {
                sendJson(ex, 405, SimpleJson.error("Method not allowed", 405));
            }
            return;
        }

        // ── /api/jobs/{id}/console ────────────────────────────────────────
        // NEW ENDPOINT: retrieve console output
        if (segments.length == 2 && "console".equals(segments[1])) {
            if ("GET".equals(httpMethod)) {
                handleConsole(ex, jobId);
            } else {
                sendJson(ex, 405, SimpleJson.error("Method not allowed", 405));
            }
            return;
        }

        sendJson(ex, 404, SimpleJson.error("Unknown path", 404));
    }

    // ── GET /api/jobs/{id}/console ────────────────────────────────────────

    /**
     * Retrieve the console output (stdout/stderr) captured during algorithm
     * execution.
     *
     * Response (200):
     * {
     *   "jobId": "550e8400-e29b-41d4-a716-446655440000",
     *   "consoleOutput": "============= APRIORI-FAST 2.65 - STATS =============\n...",
     *   "lines": 42
     * }
     *
     * Response (404): job not found
     * Response (410): job pending/running (console not yet available)
     */
    private void handleConsole(HttpExchange ex, String jobId) throws Exception {
        Job job = jobManager.getJob(jobId);
        if (job == null) {
            sendJson(ex, 404, SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }

        JobStatus status = job.getStatus();

        // Console is only available after the job has started
        if (status == JobStatus.PENDING) {
            sendJson(ex, 410,
                    SimpleJson.error(
                            "Job " + jobId + " is still pending — " +
                            "console output not yet available.", 410));
            return;
        }

        // Read console.log from the job's work directory
        String consolePath = job.getConsolePath();
        if (consolePath == null) {
            sendJson(ex, 410,
                    SimpleJson.error(
                            "Job " + jobId + " has no work directory — " +
                            "console output is not available.", 410));
            return;
        }

        java.nio.file.Path consoleFile =
                java.nio.file.Paths.get(consolePath);

        if (!java.nio.file.Files.exists(consoleFile)) {
            // Job is running or just finished but console.log doesn't exist yet
            // (race condition or job hasn't started yet)
            if (status == JobStatus.RUNNING) {
                sendJson(ex, 410,
                        SimpleJson.error(
                                "Job " + jobId + " is running — " +
                                "console output not yet available.", 410));
            } else {
                // Job finished but no console.log (shouldn't happen)
                sendJson(ex, 500,
                        SimpleJson.error(
                                "Job " + jobId + " has no console output file.",
                                500));
            }
            return;
        }

        // Read console output
        String console = java.nio.file.Files.readString(consoleFile);
        int lineCount = (int) console.lines().count();

        String resp = SimpleJson.object()
                .put("jobId",           job.getJobIdString())
                .put("consoleOutput",   console)
                .put("lines",           lineCount)
                .put("status",          job.getStatus().name())
                .build();
        sendJson(ex, 200, resp);
    }

    // ── Existing methods (handleList, handleStatus, handleResult, 
    //    handleDelete, buildJobDetail) remain unchanged ──────────────────

    // ══════════════════════════════════════════════════════════════════════
    //  HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    // ── GET /api/jobs ──────────────────────────────────────────────────────

    private void handleList(HttpExchange ex) throws Exception {
        SimpleJson.ArrayBuilder arr = SimpleJson.array();

        for (Job job : jobManager.getAllJobs()) {
            arr.addRaw(
                    SimpleJson.object()
                            .put("jobId",         job.getJobIdString())
                            .put("algorithmName", job.getAlgorithmName())
                            .put("status",        job.getStatus().name())
                            .put("submittedAt",   job.getSubmittedAt().toString())
                            .build()
            );
        }

        String resp = SimpleJson.object()
                .put("count", jobManager.totalJobCount())
                .putArray("jobs", arr)
                .build();

        sendJson(ex, 200, resp);
    }

    // ── GET /api/jobs/{id} ─────────────────────────────────────────────────

    private void handleStatus(HttpExchange ex, String jobId) throws Exception {
        Job job = jobManager.getJob(jobId);
        if (job == null) {
            sendJson(ex, 404,
                    SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }
        sendJson(ex, 200, buildJobDetail(job));
    }

    
    // ── GET /api/jobs/{id}/result ──────────────────────────────────────────

    private void handleResult(HttpExchange ex, String jobId) throws Exception {
        Job job = jobManager.getJob(jobId);
        if (job == null) {
            sendJson(ex, 404,
                    SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }

        JobStatus status = job.getStatus();

        if (status == JobStatus.PENDING || status == JobStatus.RUNNING) {
            sendJson(ex, 409,
                    SimpleJson.error(
                            "Job " + jobId + " is still " + status.name() +
                            " — result not yet available.", 409));
            return;
        }

        if (status == JobStatus.FAILED) {
            sendJson(ex, 422,
                    SimpleJson.error(
                            "Job " + jobId + " failed: " +
                            job.getErrorMessage(), 422));
            return;
        }

        // DONE — return result
        String resp = SimpleJson.object()
                .put("jobId",           job.getJobIdString())
                .put("outputData",      job.getResultData())
                .put("outputEncoding",  "plain")
                .put("executionTimeMs", job.getExecutionTimeMs())
                .build();

        sendJson(ex, 200, resp);
    }

    // ── DELETE /api/jobs/{id} ──────────────────────────────────────────────

    private void handleDelete(HttpExchange ex, String jobId) throws Exception {
        boolean deleted = jobManager.deleteJob(jobId);
        if (!deleted) {
            sendJson(ex, 404,
                    SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }
        String resp = SimpleJson.object()
                .put("jobId",   jobId)
                .put("deleted", true)
                .build();
        sendJson(ex, 200, resp);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build a full job-detail JSON string.
     * All fields are included; nullable fields write JSON null.
     */
    private static String buildJobDetail(Job job) {
        return SimpleJson.object()
                .put("jobId",
                        job.getJobIdString())
                .put("algorithmName",
                        job.getAlgorithmName())
                .put("status",
                        job.getStatus().name())
                .put("submittedAt",
                        job.getSubmittedAt().toString())
                .put("startedAt",
                        job.getStartedAt()  != null
                                ? job.getStartedAt().toString()  : null)
                .put("finishedAt",
                        job.getFinishedAt() != null
                                ? job.getFinishedAt().toString() : null)
                .put("executionTimeMs",
                        job.getExecutionTimeMs())
                .put("errorMessage",
                        job.getErrorMessage())
                .build();
    }
}