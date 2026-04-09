package ca.pfv.spmf.server.http;

import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.algorithmmanager.DescriptionOfParameter;
import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.util.ApiKeyFilter;
import ca.pfv.spmf.server.util.SimpleJson;

import com.sun.net.httpserver.HttpExchange;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * GET /api/algorithms          → list all algorithms
 * GET /api/algorithms/{name}   → describe one algorithm
 */
public final class AlgorithmHandler extends BaseHandler {

    private final SpmfCatalogue catalogue;

    /** Cached full-list JSON (computed once on first request, never changes). */
    private volatile String cachedListJson;

    public AlgorithmHandler(ServerConfig config,
                            SpmfCatalogue catalogue,
                            ApiKeyFilter apiKeyFilter) {
        super(apiKeyFilter);
        this.catalogue = catalogue;
    }

    @Override
    protected void doHandle(HttpExchange ex) throws Exception {
        if (!"GET".equals(method(ex))) {
            sendJson(ex, 405, SimpleJson.error("Method not allowed", 405));
            return;
        }

        String[] segments = pathSegments(ex, "/api/algorithms");

        if (segments.length == 0) {
            handleList(ex);
        } else {
            String name = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
            handleDescribe(ex, name);
        }
    }

    // ── GET /api/algorithms ────────────────────────────────────────────────

    private void handleList(HttpExchange ex) throws Exception {
        if (cachedListJson == null) {
            // SimpleJson.ArrayBuilder accessed as a static nested class
            SimpleJson.ArrayBuilder arr = SimpleJson.array();
            for (DescriptionOfAlgorithm desc : catalogue.getAllDescriptors()) {
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

    private void handleDescribe(HttpExchange ex, String name) throws Exception {
        DescriptionOfAlgorithm desc = catalogue.getDescriptor(name);
        if (desc == null) {
            sendJson(ex, 404,
                    SimpleJson.error("Algorithm not found: " + name, 404));
            return;
        }
        sendJson(ex, 200, descToJson(desc));
    }

    // ── descriptor → JSON string ───────────────────────────────────────────

    /**
     * Convert a {@link DescriptionOfAlgorithm} to a compact JSON string.
     * Reused by the list handler (embedded as raw) and the describe handler.
     */
    public static String descToJson(DescriptionOfAlgorithm desc) {

        // Input file types array
        SimpleJson.ArrayBuilder inTypes = SimpleJson.array();
        if (desc.getInputFileTypes() != null) {
            for (String t : desc.getInputFileTypes()) {
                inTypes.add(t);
            }
        }

        // Output file types array
        SimpleJson.ArrayBuilder outTypes = SimpleJson.array();
        if (desc.getOutputFileTypes() != null) {
            for (String t : desc.getOutputFileTypes()) {
                outTypes.add(t);
            }
        }

        // Parameters array (or null)
        DescriptionOfParameter[] params = desc.getParametersDescription();
        String paramsRaw;
        if (params == null) {
            paramsRaw = "null";
        } else {
            SimpleJson.ArrayBuilder paramsArr = SimpleJson.array();
            for (DescriptionOfParameter p : params) {
                String typeStr = (p.getParameterType() != null)
                        ? p.getParameterType().toString() : null;
                paramsArr.addRaw(
                        SimpleJson.object()
                                .put("name",          p.getName())
                                .put("example",       p.getExample())
                                .put("parameterType", typeStr)
                                .put("isOptional",    p.isOptional())
                                .build()
                );
            }
            paramsRaw = paramsArr.build();
        }

        // Algorithm type (enum → string, null-safe)
        String algoType = (desc.getAlgorithmType() != null)
                ? desc.getAlgorithmType().toString() : null;

        return SimpleJson.object()
                .put("name",                      desc.getName())
                .put("implementationAuthorNames",  desc.getImplementationAuthorNames())
                .put("algorithmCategory",          desc.getAlgorithmCategory())
                .put("documentationURL",           desc.getURLOfDocumentation())
                .put("algorithmType",              algoType)
                .putArray("inputFileTypes",        inTypes)
                .putArray("outputFileTypes",       outTypes)
                .putRaw("parameters",              paramsRaw)
                .put("numberOfMandatoryParameters",
                        desc.getNumberOfMandatoryParameters())
                .build();
    }
}