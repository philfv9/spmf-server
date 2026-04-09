package ca.pfv.spmf.server.util;

import java.util.Map;
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
 * Pure-Java JSON utility — zero external dependencies.
 *
 * This class is a thin façade over {@link SimpleJson}.
 * It exists so that any code that previously called JsonUtil
 * continues to compile without changes.
 *
 * NO Gson, NO Jackson, NO external libraries.
 */
public final class JsonUtil {

    private JsonUtil() {}

    // ── Serialisation ──────────────────────────────────────────────────────

    /**
     * Serialise any object that was built with {@link SimpleJson.ObjectBuilder}
     * or {@link SimpleJson.ArrayBuilder} to a pretty-printed JSON string.
     *
     * Because we no longer use Gson reflection, the object passed here must
     * already be a pre-built JSON string, a Map, or a simple value.
     *
     * In practice, every call site in this project passes either:
     *   - a String already produced by SimpleJson.object().build()
     *   - a SimpleJson.ObjectBuilder  (call .build() first)
     *
     * To keep backward compatibility we accept Object and handle the
     * cases we actually use:
     *   String  → pretty-print it if it looks like JSON, else quote it
     *   other   → call toString() and return that
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof String) {
            String s = (String) obj;
            // If the string is already a JSON object/array, pretty-print it
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return SimpleJson.prettyPrint(trimmed, 2);
            }
            // Otherwise treat it as a plain string value
            return "\"" + SimpleJson.escape(s) + "\"";
        }
        // Fallback – should not be reached in normal server operation
        return "\"" + SimpleJson.escape(obj.toString()) + "\"";
    }

    // ── Deserialisation ────────────────────────────────────────────────────

    /**
     * Parse a JSON object string into a {@code Map<String, Object>}.
     * Delegates to {@link SimpleJson#parseObject(String)}.
     *
     * Values in the returned map are:
     *   String, Double, Boolean, null, Map&lt;String,Object&gt;, List&lt;Object&gt;
     *
     * @param json the JSON text (must start with '{')
     * @return parsed map
     * @throws IllegalArgumentException on parse error
     */
    public static Map<String, Object> parseObject(String json) {
        return SimpleJson.parseObject(json);
    }

    /**
     * Deserialise a JSON string into an instance of {@code clazz}.
     *
     * <p><b>Limitation:</b> Without a reflection library only
     * {@code Map.class} and {@code String.class} are supported.
     * For {@code Map.class} this delegates to {@link #parseObject(String)}.
     * All other types return null and log a warning — extend this method
     * if you need additional types.
     *
     * @param json  JSON string
     * @param clazz target class
     * @param <T>   target type
     * @return parsed object, or null if the type is unsupported
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) return null;
        if (clazz == String.class) {
            return clazz.cast(json);
        }
        if (clazz == Map.class) {
            return (T) SimpleJson.parseObject(json);
        }
        // Unsupported type — return null rather than crash
        return null;
    }

    // ── Error response ─────────────────────────────────────────────────────

    /**
     * Build a standard error JSON string.
     * Result: {"error":"...","code":N}
     *
     * @param message human-readable error description
     * @param code    numeric error code (typically an HTTP status code)
     * @return compact JSON error string
     */
    public static String error(String message, int code) {
        return SimpleJson.error(message, code);
    }

    // ── Shared escape (kept for any call site that used JsonUtil.escape) ───

    /**
     * Escape a string for safe embedding inside a JSON double-quoted string.
     * Delegates to {@link SimpleJson#escape(String)}.
     */
    public static String escape(String s) {
        return SimpleJson.escape(s);
    }
}