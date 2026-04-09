package ca.pfv.spmf.server.http;

import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.job.Job;
import ca.pfv.spmf.server.job.JobManager;
import ca.pfv.spmf.server.spmfexecutor.ParameterValidator;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.SimpleJson;

import com.sun.net.httpserver.HttpExchange;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
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
 * POST /api/run
 *
 * Expected JSON body:
 * {
 *   "algorithmName": "Apriori_association_rules",
 *   "parameters":    ["0.4", "0.8"],
 *   "inputData":     "1 3 4\n1 2 4\n...",
 *   "inputEncoding": "plain"     (or "base64")
 * }
 *
 * Response 202:
 * {
 *   "jobId":         "...",
 *   "status":        "PENDING",
 *   "algorithmName": "...",
 *   "submittedAt":   "..."
 * }
 */
public final class RunHandler extends BaseHandler {

    private final ServerConfig       config;
    private final JobManager         jobManager;
    private final SpmfCatalogue      catalogue;
    private final ParameterValidator validator = new ParameterValidator();

    public RunHandler(ServerConfig config,
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

        if (!"POST".equals(method(ex))) {
            sendJson(ex, 405, SimpleJson.error("Method not allowed — use POST", 405));
            return;
        }

        // ── 1. Read body ───────────────────────────────────────────────────
        int maxBytes = config.getMaxInputSizeMb() * 1024 * 1024;
        String bodyStr;
        try {
            bodyStr = readBody(ex, maxBytes);
        } catch (Exception e) {
            sendJson(ex, 413, SimpleJson.error("Request body too large", 413));
            return;
        }

        // ── 2. Parse JSON — uses SimpleJson.parseObject ────────────────────
        Map<String, Object> body;
        try {
            body = SimpleJson.parseObject(bodyStr);
        } catch (Exception e) {
            sendJson(ex, 400,
                    SimpleJson.error("Invalid JSON: " + e.getMessage(), 400));
            return;
        }

        // ── 3. Extract fields ──────────────────────────────────────────────
        String algorithmName = getString(body, "algorithmName");
        if (algorithmName == null || algorithmName.isBlank()) {
            sendJson(ex, 400,
                    SimpleJson.error("Missing required field: algorithmName", 400));
            return;
        }

        String inputData = getString(body, "inputData");
        if (inputData == null) {
            sendJson(ex, 400,
                    SimpleJson.error("Missing required field: inputData", 400));
            return;
        }

        // Optional base64 decoding
        String encoding = getString(body, "inputEncoding");
        if ("base64".equalsIgnoreCase(encoding)) {
            try {
                inputData = new String(Base64.getDecoder().decode(inputData));
            } catch (Exception e) {
                sendJson(ex, 400,
                        SimpleJson.error("inputData is not valid base64", 400));
                return;
            }
        }

        List<String> parameters = getStringList(body, "parameters");

        // ── 4. Validate algorithm name ─────────────────────────────────────
        DescriptionOfAlgorithm desc = catalogue.getDescriptor(algorithmName);
        if (desc == null) {
            sendJson(ex, 404,
                    SimpleJson.error("Unknown algorithm: " + algorithmName, 404));
            return;
        }

        // ── 5. Validate parameters ─────────────────────────────────────────
        String paramErr = validator.validate(desc, parameters);
        if (paramErr != null) {
            sendJson(ex, 400, SimpleJson.error(paramErr, 400));
            return;
        }

        // ── 6. Submit job ──────────────────────────────────────────────────
        Job job;
        try {
            job = jobManager.submit(algorithmName, parameters, inputData);
        } catch (RejectedExecutionException e) {
            sendJson(ex, 503,
                    SimpleJson.error(
                            "Server busy — job queue is full. Try again later.", 503));
            return;
        }

        // ── 7. Return 202 Accepted ─────────────────────────────────────────
        String resp = SimpleJson.object()
                .put("jobId",         job.getJobIdString())
                .put("status",        job.getStatus().name())
                .put("algorithmName", job.getAlgorithmName())
                .put("submittedAt",   job.getSubmittedAt().toString())
                .build();
        sendJson(ex, 202, resp);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Extract a String value from the parsed map, or null if absent. */
    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return (v != null) ? v.toString() : null;
    }

    /**
     * Extract a JSON array of strings from the parsed map.
     * The SimpleJson parser represents JSON arrays as {@code List<Object>},
     * with each element being a String, Double, Boolean, null, Map, or List.
     * We convert every element to String via toString().
     */
    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        List<String> result = new ArrayList<>();
        Object v = map.get(key);
        if (v instanceof List) {
            for (Object item : (List<?>) v) {
                result.add(item != null ? item.toString() : "");
            }
        }
        return result;
    }
}