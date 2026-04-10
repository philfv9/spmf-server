package ca.pfv.spmf.server.util;

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

import java.util.Map;

/**
 * Thin façade over {@link SimpleJson} that provides a stable, backward-
 * compatible JSON serialisation and deserialisation API for the SPMF-Server.
 * <p>
 * This class exists so that call sites which previously depended on a Gson/
 * Jackson-backed {@code JsonUtil} continue to compile without modification.
 * Internally every operation is delegated to {@link SimpleJson}, which has
 * zero external dependencies.
 * <p>
 * <b>Supported round-trip types</b> (no reflection involved):
 * <ul>
 *   <li>JSON strings produced by {@link SimpleJson.ObjectBuilder} or
 *       {@link SimpleJson.ArrayBuilder}</li>
 *   <li>{@code Map<String, Object>} returned by {@link #parseObject(String)}</li>
 *   <li>Primitive wrappers: {@link String}, {@link Double},
 *       {@link Boolean}, {@code null}</li>
 * </ul>
 *
 * @author Philippe Fournier-Viger
 * @see SimpleJson
 */
public final class JsonUtil {

    /** Prevent instantiation — all methods are static. */
    private JsonUtil() {}

    // ── Serialisation ──────────────────────────────────────────────────────

    /**
     * Convert an object to a JSON string.
     * <p>
     * Supported inputs:
     * <ul>
     *   <li>{@code null} → {@code "null"}</li>
     *   <li>A {@link String} that already looks like a JSON object or array
     *       (starts with {@code '{'} or {@code '['}) → pretty-printed as-is.</li>
     *   <li>Any other {@link String} → returned as a JSON-escaped quoted
     *       string.</li>
     *   <li>Any other object → {@code toString()} is called and the result is
     *       returned as a JSON-escaped quoted string.</li>
     * </ul>
     *
     * @param obj the object to serialise
     * @return JSON representation
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof String) {
            String s       = (String) obj;
            String trimmed = s.trim();
            // If the string is already a JSON structure, pretty-print it
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return SimpleJson.prettyPrint(trimmed, 2);
            }
            // Otherwise treat it as a plain string value and quote it
            return "\"" + SimpleJson.escape(s) + "\"";
        }
        // Fallback for any other type (should not be reached in normal use)
        return "\"" + SimpleJson.escape(obj.toString()) + "\"";
    }

    // ── Deserialisation ────────────────────────────────────────────────────

    /**
     * Parse a JSON object string into a {@code Map<String, Object>}.
     * <p>
     * Delegates directly to {@link SimpleJson#parseObject(String)}.
     * Values in the returned map may be:
     * {@link String}, {@link Double}, {@link Boolean}, {@code null},
     * {@code Map<String,Object>}, or {@code List<Object>}.
     *
     * @param json JSON text that must start with {@code '{'}
     * @return a {@link java.util.LinkedHashMap} preserving key insertion order
     * @throws IllegalArgumentException if the input is null, blank, or
     *                                  not a JSON object
     */
    public static Map<String, Object> parseObject(String json) {
        return SimpleJson.parseObject(json);
    }

    /**
     * Deserialise a JSON string into an instance of {@code clazz}.
     * <p>
     * <b>Limitation:</b> Only two target types are supported without a
     * reflection library:
     * <ul>
     *   <li>{@code String.class} — returns the raw JSON string unchanged.</li>
     *   <li>{@code Map.class}    — delegates to {@link #parseObject(String)}.</li>
     * </ul>
     * All other types cause this method to return {@code null}.
     * Extend this method if additional target types are needed.
     *
     * @param json  JSON string to deserialise
     * @param clazz target class
     * @param <T>   target type
     * @return parsed object, or {@code null} if the target type is unsupported
     *         or {@code json} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) {
            return null;
        }
        if (clazz == String.class) {
            return clazz.cast(json);
        }
        if (clazz == Map.class) {
            return (T) SimpleJson.parseObject(json);
        }
        // Unsupported target type — return null rather than throw
        return null;
    }

    // ── Error response builder ─────────────────────────────────────────────

    /**
     * Build a standard two-field error JSON string.
     * <p>
     * Result format: {@code {"error":"<message>","code":<code>}}
     *
     * @param message human-readable error description
     * @param code    numeric error code (typically an HTTP status code)
     * @return compact JSON error string
     */
    public static String error(String message, int code) {
        return SimpleJson.error(message, code);
    }

    // ── Shared escape helper ───────────────────────────────────────────────

    /**
     * Escape a Java string for safe embedding inside a JSON double-quoted
     * string value.
     * <p>
     * Delegates to {@link SimpleJson#escape(String)}.
     *
     * @param s the string to escape; {@code null} returns {@code ""}
     * @return JSON-safe escaped string (without surrounding quotes)
     */
    public static String escape(String s) {
        return SimpleJson.escape(s);
    }
}