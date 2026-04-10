package ca.pfv.spmf.server.util;

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

import ca.pfv.spmf.server.ServerConfig;

/**
 * HTTP request filter that enforces API-key authentication.
 * <p>
 * When API-key security is enabled in the server configuration (i.e.
 * {@link ServerConfig#isApiKeyEnabled()} returns {@code true}), every
 * incoming HTTP request must carry the correct key in the
 * {@value #API_KEY_HEADER} request header. Requests that omit or supply an
 * incorrect key should be rejected by the caller with HTTP 401.
 * <p>
 * When API-key security is disabled (the {@code security.apiKey} property is
 * empty), {@link #isAuthorised(HttpExchange)} always returns {@code true} and
 * all requests are allowed through.
 *
 * @author Philippe Fournier-Viger
 * @see ServerConfig#isApiKeyEnabled()
 * @see ServerConfig#getApiKey()
 */
public class ApiKeyFilter {

    /**
     * Name of the HTTP request header that must carry the API key.
     * Standard practice for bearer-token style API keys.
     */
    private static final String API_KEY_HEADER = "X-API-Key";

    /** Server configuration used to look up the expected API key. */
    private final ServerConfig config;

    /**
     * Construct an {@code ApiKeyFilter} backed by the given configuration.
     *
     * @param config server configuration (must not be {@code null})
     */
    public ApiKeyFilter(ServerConfig config) {
        this.config = config;
    }

    /**
     * Determine whether the given HTTP request is authorised.
     * <p>
     * The request is considered authorised when either:
     * <ul>
     *   <li>API-key security is disabled ({@link ServerConfig#isApiKeyEnabled()}
     *       returns {@code false}), <em>or</em></li>
     *   <li>the {@value #API_KEY_HEADER} request header is present and its
     *       value exactly matches the configured API key.</li>
     * </ul>
     * The comparison is case-sensitive and performed with
     * {@link String#equals(Object)} to prevent timing attacks via early exit
     * (note: for production-grade security a constant-time comparison should be
     * used instead).
     *
     * @param exchange the incoming HTTP exchange whose headers are inspected
     * @return {@code true} if the request is authorised, {@code false} otherwise
     */
    public boolean isAuthorised(HttpExchange exchange) {
        // If no API key has been configured, all requests are allowed
        if (!config.isApiKeyEnabled()) {
            return true;
        }

        // Extract the value of the X-API-Key header (null if absent)
        String provided = exchange.getRequestHeaders().getFirst(API_KEY_HEADER);

        // Authorise only when the provided key exactly matches the configured one
        return config.getApiKey().equals(provided);
    }
}