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
import ca.pfv.spmf.server.spmfexecutor.ParameterValidator;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * HTTP handler for the algorithm job submission endpoint.
 * <p>
 * Registered at {@code POST /api/run} by {@link HttpServerBootstrap}.
 * <p>
 * <b>Request</b> ({@code Content-Type: application/json}):
 * <pre>
 * {
 *   "algorithmName": "Apriori",
 *   "parameters":    ["0.4", "0.8"],
 *   "inputData":     "1 3 4\n1 2 4\n...",
 *   "inputEncoding": "plain"
 * }
 * </pre>
 * {@code inputEncoding} is optional and defaults to {@code "plain"}.
 * Set it to {@code "base64"} to transmit binary-safe input data.
 * <p>
 * <b>Response (202 Accepted)</b> — the job was queued successfully:
 * <pre>
 * {
 *   "jobId":         "550e8400-e29b-41d4-a716-446655440000",
 *   "status":        "PENDING",
 *   "algorithmName": "Apriori",
 *   "submittedAt":   "2026-01-01T00:00:00Z"
 * }
 * </pre>
 * <b>Error responses:</b>
 * <ul>
 *   <li>405 — non-POST method</li>
 *   <li>413 — request body exceeds {@link ServerConfig#getMaxInputSizeMb()}</li>
 *   <li>400 — malformed JSON, missing required fields, or invalid parameters</li>
 *   <li>404 — algorithm name not found in the catalogue</li>
 *   <li>503 — job submission queue is full</li>
 * </ul>
 *
 * @author Philippe Fournier-Viger
 * @see JobManager#submit(String, List, String)
 * @see ParameterValidator
 * @see SpmfCatalogue
 */
public final class RunHandler extends BaseHandler {

    // ── Dependencies ───────────────────────────────────────────────────────

    /** Server configuration used to enforce the maximum input body size. */
    private final ServerConfig config;

    /** Job manager that accepts and tracks algorithm execution requests. */
    private final JobManager jobManager;

    /** SPMF algorithm catalogue used to validate the requested algorithm name. */
    private final SpmfCatalogue catalogue;

    /**
     * Validator that checks parameter count and type compatibility before
     * job submission.
     */
    private final ParameterValidator validator = new ParameterValidator();

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct a {@code RunHandler}.
     *
     * @param config       server configuration; must not be {@code null}
     * @param jobManager   the job manager that will receive submitted jobs;
     *                     must not be {@code null}
     * @param catalogue    SPMF algorithm catalogue for name validation;
     *                     must not be {@code null}
     * @param apiKeyFilter API-key filter passed to {@link BaseHandler};
     *                     must not be {@code null}
     */
    public RunHandler(ServerConfig config,
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
     * Handle a {@code POST /api/run} request.
     * <p>
     * Execution pipeline:
     * <ol>
     *   <li>Verify the HTTP method is {@code POST}.</li>
     *   <li>Read and size-check the request body.</li>
     *   <li>Parse the JSON body.</li>
     *   <li>Extract and validate required fields
     *       ({@code algorithmName}, {@code inputData}).</li>
     *   <li>Optionally decode Base64-encoded input data.</li>
     *   <li>Verify the algorithm name against the catalogue.</li>
     *   <li>Validate parameter count and types.</li>
     *   <li>Submit the job to the {@link JobManager}.</li>
     *   <li>Return HTTP 202 with the new job's metadata.</li>
     * </ol>
     *
     * @param ex the HTTP exchange to handle
     * @throws Exception if an unexpected I/O error occurs
     */
    @Override
    protected void doHandle(HttpExchange ex) throws Exception {

        // ── Step 1: Only POST is accepted ──────────────────────────────────
        if (!"POST".equals(method(ex))) {
            sendJson(ex, 405,
                    SimpleJson.error("Method not allowed — use POST.", 405));
            return;
        }

        // ── Step 2: Read and size-check the request body ───────────────────
        int    maxBytes = config.getMaxInputSizeMb() * 1024 * 1024;
        String bodyStr;
        try {
            bodyStr = readBody(ex, maxBytes);
        } catch (Exception e) {
            sendJson(ex, 413,
                    SimpleJson.error("Request body exceeds the configured limit of "
                            + config.getMaxInputSizeMb() + " MB.", 413));
            return;
        }

        // ── Step 3: Parse the JSON body ────────────────────────────────────
        Map<String, Object> body;
        try {
            body = SimpleJson.parseObject(bodyStr);
        } catch (Exception e) {
            sendJson(ex, 400,
                    SimpleJson.error("Invalid JSON body: " + e.getMessage(), 400));
            return;
        }

        // ── Step 4: Extract required fields ───────────────────────────────
        String algorithmName = getString(body, "algorithmName");
        if (algorithmName == null || algorithmName.isBlank()) {
            sendJson(ex, 400,
                    SimpleJson.error("Missing required field: 'algorithmName'.", 400));
            return;
        }

        String inputData = getString(body, "inputData");
        if (inputData == null) {
            sendJson(ex, 400,
                    SimpleJson.error("Missing required field: 'inputData'.", 400));
            return;
        }

        // ── Step 5: Optionally decode Base64-encoded input data ────────────
        String encoding = getString(body, "inputEncoding");
        if ("base64".equalsIgnoreCase(encoding)) {
            try {
                inputData = new String(Base64.getDecoder().decode(inputData));
            } catch (Exception e) {
                sendJson(ex, 400,
                        SimpleJson.error(
                                "'inputData' is not valid Base64-encoded content.",
                                400));
                return;
            }
        }

        // Extract the optional parameters array (defaults to an empty list)
        List<String> parameters = getStringList(body, "parameters");

        // ── Step 6: Validate the algorithm name against the catalogue ──────
        DescriptionOfAlgorithm desc = catalogue.getDescriptor(algorithmName);
        if (desc == null) {
            sendJson(ex, 404,
                    SimpleJson.error("Unknown algorithm: '" + algorithmName
                            + "'. Use GET /api/algorithms to list available algorithms.",
                            404));
            return;
        }

        // ── Step 7: Validate parameter count and types ─────────────────────
        String paramError = validator.validate(desc, parameters);
        if (paramError != null) {
            sendJson(ex, 400, SimpleJson.error(paramError, 400));
            return;
        }

        // ── Step 8: Submit the job to the job manager ──────────────────────
        Job job;
        try {
            job = jobManager.submit(algorithmName, parameters, inputData);
        } catch (RejectedExecutionException e) {
            // The submission queue is at capacity — ask the client to retry
            sendJson(ex, 503,
                    SimpleJson.error(
                            "Server is busy — the job queue is full. "
                            + "Please try again later.", 503));
            return;
        }

        // ── Step 9: Return 202 Accepted with the new job's metadata ────────
        sendJson(ex, 202, SimpleJson.object()
                .put("jobId",         job.getJobIdString())
                .put("status",        job.getStatus().name())
                .put("algorithmName", job.getAlgorithmName())
                .put("submittedAt",   job.getSubmittedAt().toString())
                .build());
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Extract a string value from a parsed JSON map.
     * <p>
     * If the key is present and its value is non-null, {@code toString()} is
     * called on the value object (which handles {@link String}, {@link Double},
     * {@link Boolean} transparently).  Returns {@code null} if the key is
     * absent or its value is {@code null}.
     *
     * @param map the parsed JSON object map
     * @param key the field name to look up
     * @return the string value, or {@code null} if absent
     */
    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return (value != null) ? value.toString() : null;
    }

    /**
     * Extract a JSON array of strings from a parsed JSON map.
     * <p>
     * The {@link ca.pfv.spmf.server.util.SimpleJson} parser represents JSON
     * arrays as {@code List<Object>} with elements of type {@link String},
     * {@link Double}, {@link Boolean}, {@code null}, {@link Map}, or
     * {@link List}.  Each element is converted to a {@link String} via
     * {@code toString()}; {@code null} elements become empty strings.
     * <p>
     * Returns an empty list if the key is absent or its value is not a
     * {@link List}.
     *
     * @param map the parsed JSON object map
     * @param key the field name whose value should be a JSON array
     * @return ordered list of string values; never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map,
                                              String key) {
        List<String> result = new ArrayList<>();
        Object value = map.get(key);
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                result.add(item != null ? item.toString() : "");
            }
        }
        return result;
    }
}