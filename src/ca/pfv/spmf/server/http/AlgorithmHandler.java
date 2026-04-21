package ca.pfv.spmf.server.http;

import java.util.Collection;

import com.sun.net.httpserver.HttpExchange;

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
 *
 * <p>
 * <b>URL decoding:</b> the {@code {name}} path segment is decoded by
 * {@link BaseHandler#pathSegments(HttpExchange, String)}, which correctly
 * handles percent-encoded characters including {@code %2B} → {@code +} and
 * leaves underscore ({@code _}) unchanged. No additional decoding is needed
 * in this class.
 *
 * <p>
 * <b>Performance:</b> the full algorithm list JSON is built <em>eagerly</em>
 * in the constructor on the calling thread (typically the server startup
 * thread) so that the very first HTTP request for
 * {@code GET /api/algorithms} is served instantly without any blocking
 * computation on an HTTP worker thread.  Because the catalogue is immutable
 * after startup the cache is never invalidated.
 * <p>
 * The JSON is assembled with a {@link StringBuilder} rather than repeated
 * string concatenation to keep construction time O(n) in the number of
 * algorithms.
 *
 * @author Philippe Fournier-Viger
 * @see SpmfCatalogue
 * @see DescriptionOfAlgorithm
 */
public final class AlgorithmHandler extends BaseHandler {

    /** SPMF algorithm catalogue that backs both list and describe operations. */
    private final SpmfCatalogue catalogue;

    /**
     * Full algorithm-list JSON string, built once in the constructor and
     * served for every subsequent {@code GET /api/algorithms} request.
     * <p>
     * Built eagerly at construction time (server startup) so that no HTTP
     * worker thread ever blocks on JSON serialisation.  The field is
     * {@code final} because the catalogue is immutable after startup.
     */
    private final String cachedListJson;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct an {@code AlgorithmHandler} and eagerly build the cached
     * algorithm-list JSON.
     * <p>
     * Building the cache here (on the startup thread) rather than lazily
     * on the first HTTP request means:
     * <ul>
     *   <li>The first client request is answered immediately.</li>
     *   <li>No HTTP worker thread ever blocks for an extended period.</li>
     *   <li>No synchronisation is needed — {@code cachedListJson} is
     *       {@code final} and safely published through the constructor.</li>
     * </ul>
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
        this.catalogue     = catalogue;
        this.cachedListJson = buildListJson(catalogue);
    }

    // ── BaseHandler implementation ─────────────────────────────────────────

    /**
     * Route the request to either {@link #handleList(HttpExchange)} or
     * {@link #handleDescribe(HttpExchange, String)} based on the URI path
     * segments below {@code /api/algorithms}.
     * <p>
     * The algorithm name segment is already fully decoded by
     * {@link BaseHandler#pathSegments} — no additional
     * {@link java.net.URLDecoder} call is required here.
     * <p>
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

        /*
         * pathSegments() uses getRawPath() + per-segment decoding, so:
         *   /api/algorithms/FP-Growth%2B  →  segments[0] = "FP-Growth+"  ✓
         *   /api/algorithms/CM-SPAM_1     →  segments[0] = "CM-SPAM_1"   ✓
         *   /api/algorithms/KMEANS%2B%2B  →  segments[0] = "KMEANS++"    ✓
         *
         * No additional URLDecoder.decode() call is needed or wanted here.
         */
        String[] segments = pathSegments(ex, "/api/algorithms");

        if (segments.length == 0) {
            handleList(ex);
        } else {
            // segments[0] is already decoded — pass it directly
            handleDescribe(ex, segments[0]);
        }
    }

    // ── GET /api/algorithms ────────────────────────────────────────────────

    /**
     * Return the pre-built cached JSON list of all algorithms.
     * <p>
     * This method is O(1) — it simply writes the already-serialised string
     * to the response. All the work was done at construction time by
     * {@link #buildListJson(SpmfCatalogue)}.
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
        sendJson(ex, 200, cachedListJson);
    }

    // ── GET /api/algorithms/{name} ─────────────────────────────────────────

    /**
     * Return the full JSON descriptor for a single algorithm.
     * <p>
     * The {@code name} parameter has already been percent-decoded by
     * {@link BaseHandler#pathSegments} — {@code %2B} has become {@code +},
     * underscore is unchanged, etc. The name is used directly to look up
     * the algorithm in the catalogue.
     * <p>
     * Response (200): see {@link #descToJson(DescriptionOfAlgorithm)}.<br>
     * Response (404): algorithm name not found in the catalogue.
     *
     * @param ex   the HTTP exchange to respond to
     * @param name the fully decoded algorithm name extracted from the URI
     * @throws Exception if an I/O error occurs
     */
    private void handleDescribe(HttpExchange ex, String name) throws Exception {
        DescriptionOfAlgorithm desc = catalogue.getDescriptor(name);
        if (desc == null) {
            sendJson(ex, 404,
                    SimpleJson.error(
                            "Algorithm not found: '" + name
                            + "'. Use GET /api/algorithms to list available"
                            + " algorithms.",
                            404));
            return;
        }
        sendJson(ex, 200, descToJson(desc));
    }

    // ── List JSON builder (called once at startup) ─────────────────────────

    /**
     * Build the complete {@code GET /api/algorithms} response JSON.
     * <p>
     * Uses a {@link StringBuilder} internally (via the
     * {@link #appendDescSummaryJson} helper) so that the total construction
     * time is O(n) in the number of algorithms rather than O(n²) from
     * repeated {@link String} concatenation.
     * <p>
     * Only the fields needed by the browser list view are included in the
     * summary objects ({@code name}, {@code algorithmCategory},
     * {@code algorithmType}).  Clients that need the full descriptor — with
     * parameters, file types, documentation URL, etc. — call
     * {@code GET /api/algorithms/{name}}.
     *
     * @param catalogue the SPMF catalogue; must not be {@code null}
     * @return complete JSON string ready to be sent as a response body
     */
    private static String buildListJson(SpmfCatalogue catalogue) {
        Collection<DescriptionOfAlgorithm> all = catalogue.getAllDescriptors();

        /*
         * Pre-size the StringBuilder to reduce the number of internal
         * array copies.  A typical algorithm summary object is around
         * 120 characters; allocate 150 per entry plus overhead.
         */
        StringBuilder sb = new StringBuilder(all.size() * 150 + 64);

        sb.append("{\"count\":");
        sb.append(catalogue.size());
        sb.append(",\"algorithms\":[");

        boolean first = true;
        for (DescriptionOfAlgorithm desc : all) {
            if (!first) sb.append(',');
            first = false;
            appendDescSummaryJson(sb, desc);
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Append a compact algorithm <em>summary</em> JSON object to
     * {@code sb}.
     * <p>
     * The summary contains only the fields required by the browser list /
     * grid view:
     * <ul>
     *   <li>{@code name}              — algorithm identifier</li>
     *   <li>{@code algorithmCategory} — category label for grouping</li>
     *   <li>{@code algorithmType}     — type label for badges</li>
     * </ul>
     * Keeping the summary small reduces the total payload size and avoids
     * serialising parameter arrays (which are only needed when the user
     * opens an algorithm's detail panel).
     *
     * @param sb   the builder to append to; must not be {@code null}
     * @param desc the descriptor to summarise; must not be {@code null}
     */
    private static void appendDescSummaryJson(StringBuilder sb,
                                               DescriptionOfAlgorithm desc) {
        sb.append('{');

        sb.append("\"name\":");
        appendJsonString(sb, desc.getName());

        sb.append(",\"algorithmCategory\":");
        appendJsonString(sb, desc.getAlgorithmCategory());

        sb.append(",\"algorithmType\":");
        appendJsonString(sb,
                desc.getAlgorithmType() != null
                        ? desc.getAlgorithmType().toString()
                        : null);

        sb.append('}');
    }

    // ── Shared serialisation helper ────────────────────────────────────────

    /**
     * Convert an SPMF {@link DescriptionOfAlgorithm} to a <em>full</em>
     * compact JSON string.
     * <p>
     * Used by {@link #handleDescribe} for single-algorithm lookups.  The
     * resulting object contains every field:
     * <ul>
     *   <li>{@code name}</li>
     *   <li>{@code implementationAuthorNames}</li>
     *   <li>{@code algorithmCategory}</li>
     *   <li>{@code documentationURL}</li>
     *   <li>{@code algorithmType}</li>
     *   <li>{@code inputFileTypes}               — JSON array</li>
     *   <li>{@code outputFileTypes}              — JSON array</li>
     *   <li>{@code parameters}                  — JSON array or {@code null}</li>
     *   <li>{@code numberOfMandatoryParameters}</li>
     * </ul>
     * This method is {@code public static} so that it can be reused by other
     * handlers or tests without instantiating a full handler.
     *
     * @param desc the algorithm descriptor to serialise; must not be
     *             {@code null}
     * @return compact JSON object string
     */
    public static String descToJson(DescriptionOfAlgorithm desc) {

        StringBuilder sb = new StringBuilder(512);
        sb.append('{');

        // name
        sb.append("\"name\":");
        appendJsonString(sb, desc.getName());

        // implementationAuthorNames
        sb.append(",\"implementationAuthorNames\":");
        appendJsonString(sb, desc.getImplementationAuthorNames());

        // algorithmCategory
        sb.append(",\"algorithmCategory\":");
        appendJsonString(sb, desc.getAlgorithmCategory());

        // documentationURL
        sb.append(",\"documentationURL\":");
        appendJsonString(sb, desc.getURLOfDocumentation());

        // algorithmType
        sb.append(",\"algorithmType\":");
        appendJsonString(sb,
                desc.getAlgorithmType() != null
                        ? desc.getAlgorithmType().toString()
                        : null);

        // inputFileTypes
        sb.append(",\"inputFileTypes\":[");
        if (desc.getInputFileTypes() != null) {
            boolean first = true;
            for (String t : desc.getInputFileTypes()) {
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, t);
            }
        }
        sb.append(']');

        // outputFileTypes
        sb.append(",\"outputFileTypes\":[");
        if (desc.getOutputFileTypes() != null) {
            boolean first = true;
            for (String t : desc.getOutputFileTypes()) {
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, t);
            }
        }
        sb.append(']');

        // parameters
        DescriptionOfParameter[] params = desc.getParametersDescription();
        if (params == null) {
            sb.append(",\"parameters\":null");
        } else {
            sb.append(",\"parameters\":[");
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(',');
                DescriptionOfParameter p = params[i];
                sb.append('{');

                sb.append("\"name\":");
                appendJsonString(sb, p.getName());

                sb.append(",\"example\":");
                Object ex2 = p.getExample();
                if (ex2 == null) {
                    sb.append("null");
                } else {
                    appendJsonString(sb, ex2.toString());
                }

                sb.append(",\"parameterType\":");
                appendJsonString(sb,
                        p.getParameterType() != null
                                ? p.getParameterType().toString()
                                : null);

                sb.append(",\"isOptional\":");
                sb.append(p.isOptional());

                sb.append('}');
            }
            sb.append(']');
        }

        // numberOfMandatoryParameters
        sb.append(",\"numberOfMandatoryParameters\":");
        sb.append(desc.getNumberOfMandatoryParameters());

        sb.append('}');
        return sb.toString();
    }

    // ── JSON primitive helpers ─────────────────────────────────────────────

    /**
     * Append a JSON string value (with surrounding double-quotes and proper
     * escaping) to {@code sb}, or append the literal {@code null} if
     * {@code value} is {@code null}.
     * <p>
     * Escapes the following characters as required by RFC 8259:
     * <ul>
     *   <li>{@code "} → {@code \"}</li>
     *   <li>{@code \} → {@code \\}</li>
     *   <li>control characters U+0000–U+001F → {@code \\uXXXX}</li>
     * </ul>
     * This replaces the use of {@link SimpleJson} for string fields in the
     * hot list-building path, avoiding the overhead of intermediate
     * {@link String} allocation per field.
     *
     * @param sb    the builder to append to
     * @param value the string value to encode, or {@code null}
     */
    private static void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        // Other ASCII control characters
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}