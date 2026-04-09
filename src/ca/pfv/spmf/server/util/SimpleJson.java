package ca.pfv.spmf.server.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Zero-dependency JSON builder and minimal parser.
 * Pure Java — no external libraries.
 *
 * Usage (building):
 *   String json = SimpleJson.object()
 *       .put("name",  "Apriori")
 *       .put("count", 42)
 *       .put("active", true)
 *       .putNull("optional")
 *       .putArray("tags", SimpleJson.array().add("a").add("b"))
 *       .build();
 *
 * Usage (parsing):
 *   Map&lt;String,Object&gt; map = SimpleJson.parseObject(jsonString);
 */
public final class SimpleJson {

    private SimpleJson() {}

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC FACTORY METHODS
    // ══════════════════════════════════════════════════════════════════════

    /** Start building a JSON object. */
    public static ObjectBuilder object() {
        return new ObjectBuilder();
    }

    /** Start building a JSON array. */
    public static ArrayBuilder array() {
        return new ArrayBuilder();
    }

    /**
     * Convenience: build a one-field error JSON string.
     * Result: {"error":"...","code":N}
     */
    public static String error(String message, int code) {
        return object()
                .put("error", message)
                .put("code",  code)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OBJECT BUILDER
    // ══════════════════════════════════════════════════════════════════════

    public static final class ObjectBuilder {

        private final List<String> keys   = new ArrayList<>();
        private final List<String> values = new ArrayList<>(); // raw JSON fragments

        private ObjectBuilder() {}

        /** String field (null-safe — writes JSON null if value is null). */
        public ObjectBuilder put(String key, String value) {
            keys.add(key);
            values.add(value == null ? "null" : "\"" + SimpleJson.escape(value) + "\"");
            return this;
        }

        /** boolean field. */
        public ObjectBuilder put(String key, boolean value) {
            keys.add(key);
            values.add(value ? "true" : "false");
            return this;
        }

        /** int field. */
        public ObjectBuilder put(String key, int value) {
            keys.add(key);
            values.add(Integer.toString(value));
            return this;
        }

        /** long field. */
        public ObjectBuilder put(String key, long value) {
            keys.add(key);
            values.add(Long.toString(value));
            return this;
        }

        /** double field. */
        public ObjectBuilder put(String key, double value) {
            keys.add(key);
            values.add(Double.toString(value));
            return this;
        }

        /** Explicit JSON null field. */
        public ObjectBuilder putNull(String key) {
            keys.add(key);
            values.add("null");
            return this;
        }

        /** Embed a nested ArrayBuilder. */
        public ObjectBuilder putArray(String key, ArrayBuilder arr) {
            keys.add(key);
            values.add(arr.build());
            return this;
        }

        /** Embed a nested ObjectBuilder. */
        public ObjectBuilder putObject(String key, ObjectBuilder obj) {
            keys.add(key);
            values.add(obj.build());
            return this;
        }

        /**
         * Embed a raw JSON fragment.
         * Use only when you already hold a valid JSON string.
         */
        public ObjectBuilder putRaw(String key, String rawJson) {
            keys.add(key);
            values.add(rawJson == null ? "null" : rawJson);
            return this;
        }

        /** Serialise to a compact (single-line) JSON string. */
        public String build() {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"')
                  .append(SimpleJson.escape(keys.get(i)))
                  .append("\":")
                  .append(values.get(i));
            }
            sb.append('}');
            return sb.toString();
        }

        /** Serialise to a pretty-printed JSON string (2-space indent). */
        public String buildPretty() {
            return SimpleJson.prettyPrint(build(), 2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ARRAY BUILDER
    // ══════════════════════════════════════════════════════════════════════

    public static final class ArrayBuilder {

        private final List<String> elements = new ArrayList<>(); // raw JSON fragments

        private ArrayBuilder() {}

        public ArrayBuilder add(String value) {
            elements.add(value == null ? "null" : "\"" + SimpleJson.escape(value) + "\"");
            return this;
        }

        public ArrayBuilder add(boolean value) {
            elements.add(value ? "true" : "false");
            return this;
        }

        public ArrayBuilder add(int value) {
            elements.add(Integer.toString(value));
            return this;
        }

        public ArrayBuilder add(long value) {
            elements.add(Long.toString(value));
            return this;
        }

        public ArrayBuilder addNull() {
            elements.add("null");
            return this;
        }

        public ArrayBuilder addObject(ObjectBuilder obj) {
            elements.add(obj.build());
            return this;
        }

        public ArrayBuilder addArray(ArrayBuilder arr) {
            elements.add(arr.build());
            return this;
        }

        /**
         * Add a raw JSON fragment (object or array already serialised).
         */
        public ArrayBuilder addRaw(String rawJson) {
            elements.add(rawJson == null ? "null" : rawJson);
            return this;
        }

        /** Serialise to a compact JSON array string. */
        public String build() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(elements.get(i));
            }
            sb.append(']');
            return sb.toString();
        }

        /** Serialise to a pretty-printed JSON array string. */
        public String buildPretty() {
            return SimpleJson.prettyPrint(build(), 2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MINIMAL PARSER
    //
    //  Returns:
    //    Map<String,Object>  for JSON objects
    //    List<Object>        for JSON arrays
    //    String              for JSON strings
    //    Double              for JSON numbers
    //    Boolean             for true / false
    //    null                for null
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parse a JSON object string into a {@code Map<String, Object>}.
     *
     * @param json the JSON text (must begin with '{')
     * @return parsed map
     * @throws IllegalArgumentException on parse error
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON input is null or blank");
        }
        Object result = new Parser(json.trim()).parseValue();
        if (!(result instanceof Map)) {
            throw new IllegalArgumentException(
                    "Expected a JSON object '{...}' but got: " + json);
        }
        return (Map<String, Object>) result;
    }

    /**
     * Parse a JSON array string into a {@code List<Object>}.
     *
     * @param json the JSON text (must begin with '[')
     * @return parsed list
     * @throws IllegalArgumentException on parse error
     */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON input is null or blank");
        }
        Object result = new Parser(json.trim()).parseValue();
        if (!(result instanceof List)) {
            throw new IllegalArgumentException(
                    "Expected a JSON array '[...]' but got: " + json);
        }
        return (List<Object>) result;
    }

    // ── Internal recursive-descent parser ─────────────────────────────────

    private static final class Parser {

        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) {
                throw err("Unexpected end of input");
            }
            char ch = src.charAt(pos);
            if (ch == '{') return parseJsonObject();
            if (ch == '[') return parseJsonArray();
            if (ch == '"') return parseString();
            if (ch == 't') return parseLiteral("true",  Boolean.TRUE);
            if (ch == 'f') return parseLiteral("false", Boolean.FALSE);
            if (ch == 'n') return parseLiteral("null",  null);
            if (ch == '-' || Character.isDigit(ch)) return parseNumber();
            throw err("Unexpected character: '" + ch + "'");
        }

        private Map<String, Object> parseJsonObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                char next = peek();
                if (next == '}') { pos++; break; }
                if (next == ',') { pos++; continue; }
                throw err("Expected ',' or '}' in object, got '" + next + "'");
            }
            return map;
        }

        private List<Object> parseJsonArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                char next = peek();
                if (next == ']') { pos++; break; }
                if (next == ',') { pos++; continue; }
                throw err("Expected ',' or ']' in array, got '" + next + "'");
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char ch = src.charAt(pos++);
                if (ch == '"') return sb.toString();
                if (ch == '\\') {
                    if (pos >= src.length()) break;
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u': {
                            if (pos + 4 > src.length())
                                throw err("Truncated \\u escape");
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        }
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw err("Unterminated string");
        }

        private Double parseNumber() {
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() &&
                    (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() &&
                        (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        private Object parseLiteral(String literal, Object value) {
            if (src.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw err("Expected literal '" + literal + "'");
        }

        private void skipWs() {
            while (pos < src.length() && src.charAt(pos) <= ' ') pos++;
        }

        private char peek() {
            return (pos < src.length()) ? src.charAt(pos) : 0;
        }

        private void expect(char ch) {
            if (pos >= src.length() || src.charAt(pos) != ch) {
                throw err("Expected '" + ch + "' but got '" +
                          (pos < src.length() ? src.charAt(pos) : "EOF") + "'");
            }
            pos++;
        }

        private IllegalArgumentException err(String msg) {
            int end = Math.min(pos + 30, src.length());
            return new IllegalArgumentException(
                    msg + " at position " + pos +
                    " near: [" + src.substring(pos, end) + "]");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SHARED STATIC HELPERS
    //  (called as SimpleJson.escape / SimpleJson.prettyPrint from inner classes)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Escape a string for safe embedding inside JSON double-quotes.
     * This is a static method on SimpleJson so all inner classes can call it
     * as {@code SimpleJson.escape(s)}.
     */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Re-format a compact JSON string with indentation.
     * Handles objects, arrays, strings (does not split inside strings).
     *
     * @param compact    compact JSON string
     * @param indentSize number of spaces per indent level
     * @return pretty-printed JSON string
     */
    public static String prettyPrint(String compact, int indentSize) {
        if (compact == null) return "null";
        StringBuilder sb    = new StringBuilder(compact.length() * 2);
        int           depth = 0;
        boolean       inStr = false;

        for (int i = 0; i < compact.length(); i++) {
            char ch   = compact.charAt(i);
            char prev = (i > 0) ? compact.charAt(i - 1) : 0;

            // Track string boundaries (respect escaped quotes)
            if (ch == '"' && prev != '\\') {
                inStr = !inStr;
                sb.append(ch);
                continue;
            }
            if (inStr) {
                sb.append(ch);
                continue;
            }

            switch (ch) {
                case '{':
                case '[':
                    sb.append(ch).append('\n');
                    depth++;
                    appendIndent(sb, depth, indentSize);
                    break;
                case '}':
                case ']':
                    sb.append('\n');
                    depth--;
                    appendIndent(sb, depth, indentSize);
                    sb.append(ch);
                    break;
                case ',':
                    sb.append(',').append('\n');
                    appendIndent(sb, depth, indentSize);
                    break;
                case ':':
                    sb.append(": ");
                    break;
                default:
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void appendIndent(StringBuilder sb, int depth, int indentSize) {
        for (int i = 0; i < depth * indentSize; i++) sb.append(' ');
    }
}