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
import ca.pfv.spmf.server.job.Job;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.job.JobStatus;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * HTTP handler for job management endpoints.
 * <p>
 * Registered at {@code /api/jobs} by {@link HttpServerBootstrap}.  The
 * following routes are supported:
 * <pre>
 *   GET    /api/jobs                  → list all jobs (summary)
 *   GET    /api/jobs/{id}             → full status of one job
 *   GET    /api/jobs/{id}/result      → output data when the job is DONE
 *   GET    /api/jobs/{id}/console     → stdout/stderr captured during execution
 *   DELETE /api/jobs/{id}             → delete job and its working directory
 * </pre>
 * All routes delegate the cross-cutting concerns (CORS, auth, exception
 * handling) to {@link BaseHandler}.
 *
 * @author Philippe Fournier-Viger
 * @see Job
 * @see JobManager
 * @see JobStatus
 */
public final class JobHandler extends BaseHandler {

    /** Job manager used to look up, list, and delete jobs. */
    private final JobManager jobManager;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct a {@code JobHandler}.
     *
     * @param config       server configuration (unused directly; kept for
     *                     consistency with sibling handlers)
     * @param jobManager   the job manager that owns the job registry;
     *                     must not be {@code null}
     * @param apiKeyFilter API-key filter passed to {@link BaseHandler};
     *                     must not be {@code null}
     */
    public JobHandler(ServerConfig config,
                      JobManager jobManager,
                      ApiKeyFilter apiKeyFilter) {
        super(apiKeyFilter);
        this.jobManager = jobManager;
    }

    // ── BaseHandler implementation ─────────────────────────────────────────

    /**
     * Route the request to the appropriate private handler method based on
     * the HTTP method and the URI path segments below {@code /api/jobs}.
     *
     * @param ex the HTTP exchange to handle
     * @throws Exception if an I/O error occurs while reading or writing
     */
    @Override
    protected void doHandle(HttpExchange ex) throws Exception {

        String[] segments   = pathSegments(ex, "/api/jobs");
        String   httpMethod = method(ex);

        // ── Route: /api/jobs ───────────────────────────────────────────────
        if (segments.length == 0) {
            if ("GET".equals(httpMethod)) {
                handleList(ex);
            } else {
                sendJson(ex, 405, SimpleJson.error("Method not allowed.", 405));
            }
            return;
        }

        // All remaining routes require a job ID as the first segment
        String jobId = segments[0];

        // ── Route: /api/jobs/{id} ──────────────────────────────────────────
        if (segments.length == 1) {
            switch (httpMethod) {
                case "GET":
                    handleStatus(ex, jobId);
                    break;
                case "DELETE":
                    handleDelete(ex, jobId);
                    break;
                default:
                    sendJson(ex, 405, SimpleJson.error("Method not allowed.", 405));
            }
            return;
        }

        // ── Route: /api/jobs/{id}/result ───────────────────────────────────
        if (segments.length == 2 && "result".equals(segments[1])) {
            if ("GET".equals(httpMethod)) {
                handleResult(ex, jobId);
            } else {
                sendJson(ex, 405, SimpleJson.error("Method not allowed.", 405));
            }
            return;
        }

        // ── Route: /api/jobs/{id}/console ──────────────────────────────────
        if (segments.length == 2 && "console".equals(segments[1])) {
            if ("GET".equals(httpMethod)) {
                handleConsole(ex, jobId);
            } else {
                sendJson(ex, 405, SimpleJson.error("Method not allowed.", 405));
            }
            return;
        }

        // No route matched
        sendJson(ex, 404, SimpleJson.error("Unknown path.", 404));
    }

    // ── GET /api/jobs ──────────────────────────────────────────────────────

    /**
     * Return a summary list of all jobs currently in the registry.
     * <p>
     * Response (200):
     * <pre>
     * {
     *   "count": 3,
     *   "jobs": [
     *     { "jobId": "...", "algorithmName": "...", "status": "DONE",
     *       "submittedAt": "..." },
     *     ...
     *   ]
     * }
     * </pre>
     *
     * @param ex the HTTP exchange to respond to
     * @throws Exception if an I/O error occurs
     */
    private void handleList(HttpExchange ex) throws Exception {
        SimpleJson.ArrayBuilder arr = SimpleJson.array();

        for (Job job : jobManager.getAllJobs()) {
            arr.addRaw(SimpleJson.object()
                    .put("jobId",         job.getJobIdString())
                    .put("algorithmName", job.getAlgorithmName())
                    .put("status",        job.getStatus().name())
                    .put("submittedAt",   job.getSubmittedAt().toString())
                    .build());
        }

        sendJson(ex, 200, SimpleJson.object()
                .put("count", jobManager.totalJobCount())
                .putArray("jobs", arr)
                .build());
    }

    // ── GET /api/jobs/{id} ─────────────────────────────────────────────────

    /**
     * Return the full status detail for a single job.
     * <p>
     * Response (200): see {@link #buildJobDetail(Job)}.
     * Response (404): job not found.
     *
     * @param ex    the HTTP exchange to respond to
     * @param jobId the job identifier from the URI
     * @throws Exception if an I/O error occurs
     */
    private void handleStatus(HttpExchange ex, String jobId) throws Exception {
        Job job = jobManager.getJob(jobId);
        if (job == null) {
            sendJson(ex, 404, SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }
        sendJson(ex, 200, buildJobDetail(job));
    }

    // ── GET /api/jobs/{id}/result ──────────────────────────────────────────

    /**
     * Return the algorithm output for a completed job.
     * <p>
     * Response (200) when {@link JobStatus#DONE}:
     * <pre>
     * {
     *   "jobId":           "...",
     *   "outputData":      "...",
     *   "outputEncoding":  "plain",
     *   "executionTimeMs": 1234
     * }
     * </pre>
     * Response (404): job not found.<br>
     * Response (409): job is still {@link JobStatus#PENDING} or
     *                 {@link JobStatus#RUNNING}.<br>
     * Response (422): job {@link JobStatus#FAILED}.
     *
     * @param ex    the HTTP exchange to respond to
     * @param jobId the job identifier from the URI
     * @throws Exception if an I/O error occurs
     */
    private void handleResult(HttpExchange ex, String jobId) throws Exception {
        Job job = jobManager.getJob(jobId);
        if (job == null) {
            sendJson(ex, 404, SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }

        JobStatus status = job.getStatus();

        // Result is not ready while the job is still executing
        if (status == JobStatus.PENDING || status == JobStatus.RUNNING) {
            sendJson(ex, 409,
                    SimpleJson.error("Job " + jobId + " is still "
                            + status.name() + " — result not yet available.", 409));
            return;
        }

        // A failed job has no result to return
        if (status == JobStatus.FAILED) {
            sendJson(ex, 422,
                    SimpleJson.error("Job " + jobId + " failed: "
                            + job.getErrorMessage(), 422));
            return;
        }

        // JobStatus.DONE — return the algorithm output
        sendJson(ex, 200, SimpleJson.object()
                .put("jobId",           job.getJobIdString())
                .put("outputData",      job.getResultData())
                .put("outputEncoding",  "plain")
                .put("executionTimeMs", job.getExecutionTimeMs())
                .build());
    }

    // ── GET /api/jobs/{id}/console ─────────────────────────────────────────

    /**
     * Return the captured console output (stdout + stderr) of the child JVM
     * process that ran the algorithm.
     * <p>
     * The output is read from {@code console.log} in the job's working
     * directory.
     * <p>
     * Response (200):
     * <pre>
     * {
     *   "jobId":         "...",
     *   "status":        "DONE",
     *   "consoleOutput": "...",
     *   "lines":         42
     * }
     * </pre>
     * Response (404): job not found.<br>
     * Response (410): job is {@link JobStatus#PENDING} (process not started
     *                 yet), or the console log file does not exist yet
     *                 (race condition while {@link JobStatus#RUNNING}).<br>
     * Response (500): job finished but no console log was produced
     *                 (should not happen in normal operation).
     *
     * @param ex    the HTTP exchange to respond to
     * @param jobId the job identifier from the URI
     * @throws Exception if an I/O error occurs while reading the log file
     */
    private void handleConsole(HttpExchange ex, String jobId) throws Exception {
        Job job = jobManager.getJob(jobId);
        if (job == null) {
            sendJson(ex, 404, SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }

        JobStatus status = job.getStatus();

        // The child process has not started yet — no console output exists
        if (status == JobStatus.PENDING) {
            sendJson(ex, 410,
                    SimpleJson.error("Job " + jobId
                            + " is still PENDING — console output is not yet"
                            + " available.", 410));
            return;
        }

        // Ensure the working directory path is known
        String consolePath = job.getConsolePath();
        if (consolePath == null) {
            sendJson(ex, 410,
                    SimpleJson.error("Job " + jobId
                            + " has no working directory — console output is"
                            + " unavailable.", 410));
            return;
        }

        Path consoleFile = Paths.get(consolePath);

        if (!Files.exists(consoleFile)) {
            // The log file may not exist yet during a very early RUNNING state
            // (race condition: process spawned but file not flushed to disk yet)
            if (status == JobStatus.RUNNING) {
                sendJson(ex, 410,
                        SimpleJson.error("Job " + jobId
                                + " is RUNNING — console output is not yet"
                                + " available.", 410));
            } else {
                // Terminal job with no console log — unexpected state
                sendJson(ex, 500,
                        SimpleJson.error("Job " + jobId
                                + " has no console.log file (unexpected).", 500));
            }
            return;
        }

        // Read the captured output and count its lines for the response metadata
        String consoleOutput = Files.readString(consoleFile);
        int lineCount = (int) consoleOutput.lines().count();

        sendJson(ex, 200, SimpleJson.object()
                .put("jobId",         job.getJobIdString())
                .put("status",        job.getStatus().name())
                .put("consoleOutput", consoleOutput)
                .put("lines",         lineCount)
                .build());
    }

    // ── DELETE /api/jobs/{id} ──────────────────────────────────────────────

    /**
     * Delete a job and its on-disk working directory.
     * <p>
     * Response (200):
     * <pre>
     * { "jobId": "...", "deleted": true }
     * </pre>
     * Response (404): job not found.
     *
     * @param ex    the HTTP exchange to respond to
     * @param jobId the job identifier from the URI
     * @throws Exception if an I/O error occurs
     */
    private void handleDelete(HttpExchange ex, String jobId) throws Exception {
        boolean deleted = jobManager.deleteJob(jobId);
        if (!deleted) {
            sendJson(ex, 404, SimpleJson.error("Job not found: " + jobId, 404));
            return;
        }
        sendJson(ex, 200, SimpleJson.object()
                .put("jobId",   jobId)
                .put("deleted", true)
                .build());
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Build a complete job-detail JSON object for the given job.
     * <p>
     * All fields are included; fields that have not yet been set
     * (e.g. {@code startedAt} while the job is still PENDING) are written
     * as JSON {@code null}.
     *
     * @param job the job to serialise; must not be {@code null}
     * @return compact JSON object string
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