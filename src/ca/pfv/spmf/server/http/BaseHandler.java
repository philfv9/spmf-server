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

import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.ServerLogger;
import ca.pfv.spmf.server.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Abstract base class for all SPMF-Server HTTP request handlers.
 * <p>
 * Implements {@link HttpHandler} so that every concrete subclass can be
 * registered directly with {@code HttpServer.createContext(path, handler)}.
 * <p>
 * This class handles the following cross-cutting concerns before delegating
 * to the subclass-specific {@link #doHandle(HttpExchange)} method:
 * <ol>
 *   <li><b>CORS</b> — attaches permissive {@code Access-Control-*} headers
 *       to every response and short-circuits {@code OPTIONS} pre-flight
 *       requests with HTTP 204.</li>
 *   <li><b>API-key authentication</b> — delegates to {@link ApiKeyFilter};
 *       returns HTTP 401 when the key is missing or incorrect.</li>
 *   <li><b>Top-level exception handling</b> — catches any unchecked exception
 *       thrown by {@link #doHandle} and converts it to an HTTP 500 JSON
 *       error response.</li>
 * </ol>
 * Subclasses must implement {@link #doHandle(HttpExchange)} and may use the
 * protected helper methods ({@link #sendJson}, {@link #readBody},
 * {@link #method}, {@link #pathSegments}) for common HTTP operations.
 *
 * <p>
 * <b>URL encoding note:</b>
 * {@code com.sun.net.httpserver} exposes the raw (not pre-decoded) request
 * URI via {@link HttpExchange#getRequestURI()}.  The helper
 * {@link #pathSegments(HttpExchange, String)} therefore uses
 * {@link URI#getRawPath()} to obtain the still-percent-encoded path string,
 * splits it on literal {@code '/'} characters, and then percent-decodes each
 * segment individually using
 * {@link java.net.URLDecoder#decode(String, java.nio.charset.Charset)}.
 * <p>
 * This two-step approach (split first, decode second) is critical for
 * algorithm names that contain characters which are percent-encoded in URLs:
 * <ul>
 *   <li>{@code +}  is transmitted as {@code %2B}; decoded to {@code +}.</li>
 *   <li>{@code _}  is an unreserved character and survives un-encoded.</li>
 *   <li>{@code %20} / {@code +} in a <em>query string</em> means space, but
 *       in a <em>path segment</em> only {@code %20} means space — we decode
 *       path segments with
 *       {@link java.net.URLDecoder} after replacing {@code +} with {@code %2B}
 *       so that a literal {@code +} in a name is never mis-read as a space.
 *       </li>
 * </ul>
 *
 * @author Philippe Fournier-Viger
 * @see ApiKeyFilter
 * @see SimpleJson
 */
public abstract class BaseHandler implements HttpHandler {

    /**
     * Per-class logger; uses the concrete subclass name so that log records
     * identify the actual handler that produced them.
     */
    protected final Logger log = ServerLogger.get(getClass());

    /**
     * Filter that enforces API-key authentication on every request.
     * Shared with all handlers created by the same
     * {@link HttpServerBootstrap}.
     */
    protected final ApiKeyFilter apiKeyFilter;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Construct a handler with the given API-key filter.
     *
     * @param apiKeyFilter the filter to use for authentication checks;
     *                     must not be {@code null}
     */
    protected BaseHandler(ApiKeyFilter apiKeyFilter) {
        this.apiKeyFilter = apiKeyFilter;
    }

    // ── HttpHandler entry point ────────────────────────────────────────────

    /**
     * Final implementation of {@link HttpHandler#handle(HttpExchange)}.
     * <p>
     * Executes the following pipeline in order:
     * <ol>
     *   <li>Attach CORS headers to the response.</li>
     *   <li>Return HTTP 204 for {@code OPTIONS} pre-flight requests.</li>
     *   <li>Check the API key; return HTTP 401 on failure.</li>
     *   <li>Call {@link #doHandle(HttpExchange)}; catch any exception and
     *       return HTTP 500.</li>
     * </ol>
     * Subclasses must <em>not</em> override this method — override
     * {@link #doHandle(HttpExchange)} instead.
     *
     * @param ex the incoming HTTP exchange provided by the JDK HTTP server
     * @throws IOException if a fatal I/O error occurs while sending the
     *                     response (rare; typically means the client
     *                     disconnected)
     */
    @Override
    public final void handle(HttpExchange ex) throws IOException {

        // ── Step 1: Attach permissive CORS headers ─────────────────────────
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods",
                "GET,POST,DELETE,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers",
                "Content-Type,X-API-Key");

        // ── Step 2: Short-circuit CORS pre-flight requests ─────────────────
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        // ── Step 3: API-key authentication ─────────────────────────────────
        if (!apiKeyFilter.isAuthorised(ex)) {
            sendJson(ex, 401,
                    SimpleJson.error(
                            "Unauthorised: missing or invalid X-API-Key header.",
                            401));
            return;
        }

        // ── Step 4: Delegate to the concrete handler ───────────────────────
        try {
            doHandle(ex);
        } catch (Exception e) {
            log.severe("Unhandled exception in "
                    + getClass().getSimpleName() + ": " + e.getMessage());
            try {
                sendJson(ex, 500,
                        SimpleJson.error(
                                "Internal server error: " + e.getMessage(),
                                500));
            } catch (IOException ignored) {
                // Response already committed — nothing more we can do here
            }
        }
    }

    // ── Abstract method ────────────────────────────────────────────────────

    /**
     * Handle the HTTP request after authentication and CORS have been
     * resolved.
     * <p>
     * Implementations should use
     * {@link #sendJson(HttpExchange, int, String)} to write exactly one
     * response and should not call {@link HttpExchange#close()} themselves
     * (it is called inside {@link #sendJson}).
     * <p>
     * Any {@link Exception} thrown from this method is caught by
     * {@link #handle(HttpExchange)} and converted to an HTTP 500 response.
     *
     * @param ex the HTTP exchange to handle
     * @throws Exception any exception; will be caught and converted to
     *                   HTTP 500
     */
    protected abstract void doHandle(HttpExchange ex) throws Exception;

    // ── Protected helpers ──────────────────────────────────────────────────

    /**
     * Serialise {@code json} to UTF-8 bytes and write it as the complete
     * HTTP response body with the given status code.
     * <p>
     * Sets the {@code Content-Type} header to
     * {@code application/json; charset=UTF-8} and closes the exchange.
     *
     * @param ex     the HTTP exchange to respond to
     * @param status HTTP status code (e.g. 200, 201, 400, 404, 500)
     * @param json   the JSON string to send as the response body
     * @throws IOException if an I/O error occurs while writing the response
     */
    protected void sendJson(HttpExchange ex, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    /**
     * Read the entire request body as a UTF-8 string, enforcing a hard size
     * limit.
     *
     * @param ex       the HTTP exchange whose request body is to be read
     * @param maxBytes maximum number of bytes accepted
     * @return the request body decoded as a UTF-8 string
     * @throws IOException if the body exceeds {@code maxBytes} or if an
     *                     underlying I/O error occurs
     */
    protected String readBody(HttpExchange ex, int maxBytes)
            throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            byte[] buf = is.readNBytes(maxBytes + 1);
            if (buf.length > maxBytes) {
                throw new IOException(
                        "Request body exceeds the configured limit of "
                        + maxBytes + " bytes.");
            }
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    /**
     * Return the HTTP method of the request, normalised to upper case.
     *
     * @param ex the HTTP exchange
     * @return upper-case HTTP method string, e.g. {@code "GET"}
     */
    protected String method(HttpExchange ex) {
        return ex.getRequestMethod().toUpperCase();
    }

    /**
     * Split the request URI path into individual <em>decoded</em> segments
     * that appear below the given context root.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Obtain the <b>raw</b> (still percent-encoded) path from
     *       {@link URI#getRawPath()}.  Using the raw path means we split on
     *       literal {@code '/'} characters only — a {@code %2F} sequence
     *       (encoded slash) inside a segment is not mistaken for a path
     *       separator.</li>
     *   <li>Strip the {@code contextRoot} prefix and any leading {@code '/'}
     *       left over.</li>
     *   <li>Split the remainder on {@code '/'}.</li>
     *   <li>Percent-decode each segment individually.
     *       <b>Critically</b>, before passing a segment to
     *       {@link java.net.URLDecoder}, every literal {@code '+'} in the raw
     *       segment is replaced with {@code "%2B"}.  This prevents
     *       {@link java.net.URLDecoder} from interpreting {@code '+'} as a
     *       space (which is correct for query strings but wrong for path
     *       segments).</li>
     * </ol>
     *
     * <h3>Example</h3>
     * <pre>
     * contextRoot = "/api/algorithms"
     * request URI = "/api/algorithms/FP-Growth%2B"
     *
     * raw path after prefix strip → "FP-Growth%2B"
     * after replacing + with %2B  → "FP-Growth%2B"   (no change here)
     * after URLDecoder.decode      → "FP-Growth+"     ✓
     * </pre>
     * <pre>
     * contextRoot = "/api/algorithms"
     * request URI = "/api/algorithms/CM-SPAM_1"
     *
     * raw segment  → "CM-SPAM_1"
     * decoded      → "CM-SPAM_1"   ✓  (underscore is unreserved, unchanged)
     * </pre>
     *
     * @param ex          the HTTP exchange
     * @param contextRoot the path prefix registered with
     *                    {@code HttpServer.createContext()}
     * @return array of percent-decoded path segment strings below the
     *         context root; never {@code null}; empty when the URI matches
     *         the root exactly
     */
    protected String[] pathSegments(HttpExchange ex, String contextRoot) {

        /*
         * Step 1 — use getRawPath() so that %2F inside a segment is NOT
         * treated as a path separator, and so that we control decoding
         * ourselves in step 4.
         */
        String rawPath = ex.getRequestURI().getRawPath();

        // Step 2 — strip the context root prefix
        if (rawPath.startsWith(contextRoot)) {
            rawPath = rawPath.substring(contextRoot.length());
        }

        // Strip any leading slash left after prefix removal
        if (rawPath.startsWith("/")) {
            rawPath = rawPath.substring(1);
        }

        // Empty remainder → request targeted the context root exactly
        if (rawPath.isEmpty()) {
            return new String[0];
        }

        // Step 3 — split on literal '/' (raw path, so no encoded slashes lost)
        String[] rawSegments = rawPath.split("/", -1);

        // Step 4 — decode each segment individually
        String[] decoded = new String[rawSegments.length];
        for (int i = 0; i < rawSegments.length; i++) {
            decoded[i] = decodePathSegment(rawSegments[i]);
        }
        return decoded;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Percent-decode a single URL path segment correctly.
     * <p>
     * {@link java.net.URLDecoder#decode(String, java.nio.charset.Charset)}
     * follows the {@code application/x-www-form-urlencoded} rules and treats
     * {@code '+'} as a space character.  That is correct for query-string
     * values but <em>wrong</em> for path segments, where {@code '+'} is a
     * literal plus sign and only {@code %20} means a space.
     * <p>
     * To work around this, every literal {@code '+'} in the raw segment is
     * replaced with {@code "%2B"} <em>before</em> the decoder runs.  The
     * decoder then sees {@code %2B} and produces {@code '+'} — the correct
     * result.
     * <p>
     * If decoding fails for any reason (malformed percent-escape), the
     * original raw segment is returned unchanged so that callers can still
     * attempt a lookup and return a meaningful 404 rather than a 500.
     *
     * @param rawSegment a single raw (still percent-encoded) path segment;
     *                   must not be {@code null}
     * @return the decoded segment string
     */
    private static String decodePathSegment(String rawSegment) {
        /*
         * Replace literal '+' with '%2B' BEFORE decoding so that URLDecoder
         * does not convert '+' to a space.
         *
         * Why this order?
         *   raw segment from client:  "FP-Growth%2B"
         *   after replace:            "FP-Growth%2B"  ← no change (no literal +)
         *   after URLDecoder.decode:  "FP-Growth+"    ← correct ✓
         *
         *   raw segment from client:  "algo+name"     ← literal + (unusual but valid)
         *   after replace:            "algo%2Bname"
         *   after URLDecoder.decode:  "algo+name"     ← correct ✓
         *
         *   raw segment from client:  "CM-SPAM_1"     ← underscore, no encoding needed
         *   after replace:            "CM-SPAM_1"     ← no change
         *   after URLDecoder.decode:  "CM-SPAM_1"     ← correct ✓
         */
        String safe = rawSegment.replace("+", "%2B");
        try {
            return java.net.URLDecoder.decode(safe, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Malformed percent-escape — return the raw segment so the
            // caller can issue a 404 rather than an unexpected 500.
            return rawSegment;
        }
    }
}