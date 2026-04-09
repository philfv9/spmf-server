package ca.pfv.spmf.server.util;

import com.sun.net.httpserver.HttpExchange;

import ca.pfv.spmf.server.ServerConfig;
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
 * Checks the X-API-Key header when API key security is enabled in config.
 */
public class ApiKeyFilter {

    private static final String HEADER = "X-API-Key";

    private final ServerConfig config;

    public ApiKeyFilter(ServerConfig config) {
        this.config = config;
    }

    /**
     * Returns true if the request is authorised
     * (either API key is disabled, or the header matches).
     */
    public boolean isAuthorised(HttpExchange exchange) {
        if (!config.isApiKeyEnabled()) return true;
        String provided = exchange.getRequestHeaders().getFirst(HEADER);
        return config.getApiKey().equals(provided);
    }
}