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
        // Allow any origin so that browser-based API clients and development
        // tools (e.g. Swagger UI, curl) can reach the server without issues.
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,X-API-Key");

        // ── Step 2: Short-circuit CORS pre-flight requests ─────────────────
        // Browsers send an OPTIONS request before the actual cross-origin
        // request. Respond immediately with 204 No Content.
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
                // Attempt to send a 500 response; this may fail if the response
                // headers were already committed, which is why we catch IOException
                sendJson(ex, 500,
                        SimpleJson.error(
                                "Internal server error: " + e.getMessage(), 500));
            } catch (IOException ignored) {
                // Response already committed — nothing more we can do here
            }
        }
    }

    // ── Abstract method — subclasses implement their request logic here ─────

    /**
     * Handle the HTTP request after authentication and CORS have been resolved.
     * <p>
     * Implementations should use {@link #sendJson(HttpExchange, int, String)}
     * to write exactly one response and should not call {@link HttpExchange#close()}
     * themselves (it is called inside {@link #sendJson}).
     * <p>
     * Any {@link Exception} thrown from this method is caught by
     * {@link #handle(HttpExchange)} and converted to an HTTP 500 response.
     *
     * @param ex the HTTP exchange to handle
     * @throws Exception any exception; will be caught and converted to HTTP 500
     */
    protected abstract void doHandle(HttpExchange ex) throws Exception;

    // ── Protected helpers available to all subclasses ──────────────────────

    /**
     * Serialise {@code json} to UTF-8 bytes and write it as the complete HTTP
     * response body with the given status code.
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
     * <p>
     * Reads at most {@code maxBytes + 1} bytes from the input stream; if more
     * bytes are available the request is considered too large and an
     * {@link IOException} is thrown.  This prevents a malicious or erroneous
     * client from exhausting server memory.
     *
     * @param ex       the HTTP exchange whose request body is to be read
     * @param maxBytes maximum number of bytes accepted; requests exceeding
     *                 this limit cause an {@link IOException}
     * @return the request body decoded as a UTF-8 string
     * @throws IOException if the body exceeds {@code maxBytes} or if an
     *                     underlying I/O error occurs
     */
    protected String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            // Read one extra byte to detect bodies that are exactly at the limit
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
     * <p>
     * Examples: {@code "GET"}, {@code "POST"}, {@code "DELETE"}.
     *
     * @param ex the HTTP exchange
     * @return upper-case HTTP method string
     */
    protected String method(HttpExchange ex) {
        return ex.getRequestMethod().toUpperCase();
    }

    /**
     * Split the request URI path into individual segments that appear
     * <em>below</em> the given context root.
     * <p>
     * Example: if {@code contextRoot} is {@code "/api/jobs"} and the request
     * URI is {@code "/api/jobs/abc-123/result"}, this method returns
     * {@code ["abc-123", "result"]}.
     * <p>
     * Returns an empty array when the URI matches the root exactly
     * (e.g. {@code "/api/jobs"} or {@code "/api/jobs/"}).
     *
     * @param ex          the HTTP exchange
     * @param contextRoot the path prefix registered with
     *                    {@code HttpServer.createContext()}
     * @return array of path segment strings below the context root;
     *         never {@code null}
     */
    protected String[] pathSegments(HttpExchange ex, String contextRoot) {
        String path = ex.getRequestURI().getPath();

        // Strip the context root prefix
        if (path.startsWith(contextRoot)) {
            path = path.substring(contextRoot.length());
        }

        // Strip a leading slash left after prefix removal
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        // Empty remainder means the request targeted the root exactly
        if (path.isEmpty()) {
            return new String[0];
        }

        return path.split("/");
    }
}