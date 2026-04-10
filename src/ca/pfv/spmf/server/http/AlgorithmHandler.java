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

import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.algorithmmanager.DescriptionOfParameter;
import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * HTTP handler for the algorithm catalogue endpoints.
 * <p>
 * Registered at {@code /api/algorithms} by {@link HttpServerBootstrap}.
 * Two routes are supported:
 * <ul>
 *   <li>{@code GET /api/algorithms}        — returns a summary list of all
 *       algorithms available in the server's {@link SpmfCatalogue}.</li>
 *   <li>{@code GET /api/algorithms/{name}} — returns the full descriptor
 *       of a single algorithm identified by its URL-encoded name.</li>
 * </ul>
 * Only {@code GET} requests are accepted; any other method receives HTTP 405.
 * <p>
 * <b>Performance note:</b> the full algorithm list JSON is computed once on
 * the first request and cached in {@link #cachedListJson}.  Because the
 * catalogue is immutable after server startup, this cache never needs to be
 * invalidated.
 *
 * @author Philippe Fournier-Viger
 * @see SpmfCatalogue
 * @see DescriptionOfAlgorithm
 */
public final class AlgorithmHandler extends BaseHandler {

    /** SPMF algorithm catalogue that backs both list and describe operations. */
    private final SpmfCatalogue catalogue;

    /**
     * Lazily computed, cached JSON string for the full algorithm list.
     * Built on the first {@code GET /api/algorithms} request and reused
     * for all subsequent requests.  {@code volatile} ensures the computed
     * value is visible to all HTTP worker threads without synchronization
     * overhead on subsequent reads.
     */
    private volatile String cachedListJson;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct an {@code AlgorithmHandler}.
     *
     * @param config       server configuration (unused directly; kept for
     *                     consistency with sibling handlers)
     * @param catalogue    the SPMF algorithm catalogue to expose; must not
     *                     be {@code null}
     * @param apiKeyFilter API-key filter passed to {@link BaseHandler};
     *                     must not be {@code null}
     */
    public AlgorithmHandler(ServerConfig config,
                            SpmfCatalogue catalogue,
                            ApiKeyFilter apiKeyFilter) {
        super(apiKeyFilter);
        this.catalogue = catalogue;
    }

    // ── BaseHandler implementation ─────────────────────────────────────────

    /**
     * Route the request to either {@link #handleList(HttpExchange)} or
     * {@link #handleDescribe(HttpExchange, String)} based on the URI path
     * segments below {@code /api/algorithms}.
     * Returns HTTP 405 for non-{@code GET} methods.
     *
     * @param ex the HTTP exchange to handle
     * @throws Exception if an I/O error occurs while sending the response
     */
    @Override
    protected void doHandle(HttpExchange ex) throws Exception {
        if (!"GET".equals(method(ex))) {
            sendJson(ex, 405,
                    SimpleJson.error("Method not allowed — use GET.", 405));
            return;
        }

        String[] segments = pathSegments(ex, "/api/algorithms");

        if (segments.length == 0) {
            // No name segment → return the full list
            handleList(ex);
        } else {
            // URL-decode the name segment to handle spaces and special chars
            String name = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
            handleDescribe(ex, name);
        }
    }

    // ── GET /api/algorithms ────────────────────────────────────────────────

    /**
     * Return a JSON list of all algorithms in the catalogue.
     * <p>
     * The response is built lazily on the first call and cached for all
     * subsequent requests.
     * <p>
     * Response (200):
     * <pre>
     * {
     *   "count": 123,
     *   "algorithms": [ { ... }, { ... }, ... ]
     * }
     * </pre>
     *
     * @param ex the HTTP exchange to respond to
     * @throws Exception if an I/O error occurs
     */
    private void handleList(HttpExchange ex) throws Exception {
        // Build and cache the list JSON on the first request
        if (cachedListJson == null) {
            SimpleJson.ArrayBuilder arr = SimpleJson.array();
            for (DescriptionOfAlgorithm desc : catalogue.getAllDescriptors()) {
                // Embed each descriptor as a raw pre-built JSON fragment
                arr.addRaw(descToJson(desc));
            }
            cachedListJson = SimpleJson.object()
                    .put("count", catalogue.size())
                    .putArray("algorithms", arr)
                    .build();
        }
        sendJson(ex, 200, cachedListJson);
    }

    // ── GET /api/algorithms/{name} ─────────────────────────────────────────

    /**
     * Return the full JSON descriptor for a single algorithm.
     * <p>
     * Response (200): see {@link #descToJson(DescriptionOfAlgorithm)}.<br>
     * Response (404): algorithm name not found in the catalogue.
     *
     * @param ex   the HTTP exchange to respond to
     * @param name the URL-decoded algorithm name from the URI
     * @throws Exception if an I/O error occurs
     */
    private void handleDescribe(HttpExchange ex, String name) throws Exception {
        DescriptionOfAlgorithm desc = catalogue.getDescriptor(name);
        if (desc == null) {
            sendJson(ex, 404,
                    SimpleJson.error("Algorithm not found: '" + name
                            + "'. Use GET /api/algorithms to list available"
                            + " algorithms.", 404));
            return;
        }
        sendJson(ex, 200, descToJson(desc));
    }

    // ── Shared serialisation helper ────────────────────────────────────────

    /**
     * Convert an SPMF {@link DescriptionOfAlgorithm} to a compact JSON string.
     * <p>
     * The resulting JSON object includes:
     * <ul>
     *   <li>{@code name}                      — algorithm identifier</li>
     *   <li>{@code implementationAuthorNames}  — comma-separated author list</li>
     *   <li>{@code algorithmCategory}          — high-level category label</li>
     *   <li>{@code documentationURL}           — URL to algorithm documentation</li>
     *   <li>{@code algorithmType}              — {@link ca.pfv.spmf.algorithmmanager.AlgorithmType} name</li>
     *   <li>{@code inputFileTypes}             — array of accepted input file type strings</li>
     *   <li>{@code outputFileTypes}            — array of produced output file type strings</li>
     *   <li>{@code parameters}                — array of parameter descriptors, or {@code null}</li>
     *   <li>{@code numberOfMandatoryParameters}— count of non-optional parameters</li>
     * </ul>
     * This method is {@code public static} so that it can be reused by other
     * handlers or test code without instantiating a full handler.
     *
     * @param desc the algorithm descriptor to serialise; must not be
     *             {@code null}
     * @return compact JSON object string
     */
    public static String descToJson(DescriptionOfAlgorithm desc) {

        // ── Input file type array ──────────────────────────────────────────
        SimpleJson.ArrayBuilder inTypes = SimpleJson.array();
        if (desc.getInputFileTypes() != null) {
            for (String t : desc.getInputFileTypes()) {
                inTypes.add(t);
            }
        }

        // ── Output file type array ─────────────────────────────────────────
        SimpleJson.ArrayBuilder outTypes = SimpleJson.array();
        if (desc.getOutputFileTypes() != null) {
            for (String t : desc.getOutputFileTypes()) {
                outTypes.add(t);
            }
        }

        // ── Parameter descriptor array (or JSON null if absent) ────────────
        DescriptionOfParameter[] params = desc.getParametersDescription();
        String paramsRaw;

        if (params == null) {
            paramsRaw = "null"; // algorithm declares no parameters
        } else {
            SimpleJson.ArrayBuilder paramsArr = SimpleJson.array();
            for (DescriptionOfParameter p : params) {
                // Convert the parameter type Class to its canonical string name;
                // null-safe because some descriptors omit the type
                String typeStr = (p.getParameterType() != null)
                        ? p.getParameterType().toString() : null;

                paramsArr.addRaw(SimpleJson.object()
                        .put("name",          p.getName())
                        .put("example",       p.getExample())
                        .put("parameterType", typeStr)
                        .put("isOptional",    p.isOptional())
                        .build());
            }
            paramsRaw = paramsArr.build();
        }

        // ── Algorithm type string (null-safe) ──────────────────────────────
        String algoType = (desc.getAlgorithmType() != null)
                ? desc.getAlgorithmType().toString() : null;

        return SimpleJson.object()
                .put("name",
                        desc.getName())
                .put("implementationAuthorNames",
                        desc.getImplementationAuthorNames())
                .put("algorithmCategory",
                        desc.getAlgorithmCategory())
                .put("documentationURL",
                        desc.getURLOfDocumentation())
                .put("algorithmType",
                        algoType)
                .putArray("inputFileTypes",
                        inTypes)
                .putArray("outputFileTypes",
                        outTypes)
                .putRaw("parameters",
                        paramsRaw)
                .put("numberOfMandatoryParameters",
                        desc.getNumberOfMandatoryParameters())
                .build();
    }
}