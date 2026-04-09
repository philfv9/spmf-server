package ca.pfv.spmf.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.ServerLogger;
import ca.pfv.spmf.server.util.SimpleJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
/**
 * Abstract base for all HTTP handlers.
 *
 * <p>Implements {@link HttpHandler} so every subclass is automatically
 * a valid {@code HttpHandler} that can be passed to
 * {@code HttpServer.createContext(path, handler)}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>CORS pre-flight handling</li>
 *   <li>API-key authentication check</li>
 *   <li>Top-level exception catching</li>
 *   <li>Helper methods: sendJson, readBody, method, pathSegments</li>
 * </ul>
 *
 * Subclasses only implement {@link #doHandle(HttpExchange)}.
 */
public abstract class BaseHandler implements HttpHandler {

    protected final Logger       log = ServerLogger.get(getClass());
    protected final ApiKeyFilter apiKeyFilter;

    protected BaseHandler(ApiKeyFilter apiKeyFilter) {
        this.apiKeyFilter = apiKeyFilter;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HttpHandler entry point  (called by com.sun.net.httpserver)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Final implementation of {@link HttpHandler#handle}.
     * Subclasses must NOT override this — override {@link #doHandle} instead.
     */
    @Override
    public final void handle(HttpExchange ex) throws IOException {

        // ── CORS headers ───────────────────────────────────────────────────
        ex.getResponseHeaders().add(
                "Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add(
                "Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        ex.getResponseHeaders().add(
                "Access-Control-Allow-Headers", "Content-Type,X-API-Key");

        // ── Pre-flight ─────────────────────────────────────────────────────
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        // ── API key check ──────────────────────────────────────────────────
        if (!apiKeyFilter.isAuthorised(ex)) {
            sendJson(ex, 401,
                    SimpleJson.error(
                            "Unauthorised: missing or invalid X-API-Key", 401));
            return;
        }

        // ── Delegate to subclass ───────────────────────────────────────────
        try {
            doHandle(ex);
        } catch (Exception e) {
            log.severe("Unhandled exception in "
                    + getClass().getSimpleName() + ": " + e.getMessage());
            try {
                sendJson(ex, 500,
                        SimpleJson.error(
                                "Internal server error: " + e.getMessage(), 500));
            } catch (IOException ignored) {
                // Response may already be committed — nothing we can do
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Abstract method — subclasses implement their logic here
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Handle the HTTP request.
     * Auth and CORS are already handled before this is called.
     *
     * @param ex the HTTP exchange
     * @throws Exception any exception is caught by {@link #handle} and
     *                   converted to a 500 response
     */
    protected abstract void doHandle(HttpExchange ex) throws Exception;

    // ══════════════════════════════════════════════════════════════════════
    //  Protected helpers available to all subclasses
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Write a JSON string as the complete HTTP response body.
     *
     * @param ex     the exchange
     * @param status HTTP status code (e.g. 200, 201, 400, 404)
     * @param json   compact or pretty JSON string
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
     * Read the entire request body as a UTF-8 string.
     *
     * @param ex       the exchange
     * @param maxBytes hard limit on body size; throws IOException if exceeded
     * @return body text
     * @throws IOException if body exceeds maxBytes or an I/O error occurs
     */
    protected String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            byte[] buf = is.readNBytes(maxBytes + 1);
            if (buf.length > maxBytes) {
                throw new IOException(
                        "Request body exceeds limit of " + maxBytes + " bytes.");
            }
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    /**
     * Return the HTTP method of the request, upper-cased.
     * e.g. "GET", "POST", "DELETE"
     */
    protected String method(HttpExchange ex) {
        return ex.getRequestMethod().toUpperCase();
    }

    /**
     * Split the request URI path into segments below a given context root.
     *
     * <p>Example: context root = "/api/jobs", URI = "/api/jobs/abc/result"
     * → returns ["abc", "result"]
     *
     * @param ex          the exchange
     * @param contextRoot the path registered with createContext()
     * @return array of path segments below the root; empty array if none
     */
    protected String[] pathSegments(HttpExchange ex, String contextRoot) {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith(contextRoot)) {
            path = path.substring(contextRoot.length());
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return new String[0];
        }
        return path.split("/");
    }
}