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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency JSON builder and minimal recursive-descent parser.
 * <p>
 * This utility class provides two independent facilities:
 * <ol>
 *   <li><b>Building</b> — fluent builder objects that produce compact or
 *       pretty-printed JSON strings without any external library.</li>
 *   <li><b>Parsing</b> — a minimal recursive-descent parser that converts a
 *       JSON string into standard Java collections ({@link Map}, {@link List},
 *       {@link String}, {@link Double}, {@link Boolean}, {@code null}).</li>
 * </ol>
 *
 * <b>Building example:</b>
 * <pre>{@code
 * String json = SimpleJson.object()
 *         .put("algorithm", "Apriori")
 *         .put("minSupport", 0.5)
 *         .put("active", true)
 *         .putNull("description")
 *         .putArray("tags", SimpleJson.array().add("frequent").add("itemset"))
 *         .build();
 * }</pre>
 *
 * <b>Parsing example:</b>
 * <pre>{@code
 * Map<String, Object> map = SimpleJson.parseObject(jsonString);
 * }</pre>
 *
 * @author Philippe Fournier-Viger
 */
public final class SimpleJson {

    /** Prevent instantiation — all methods are static. */
    private SimpleJson() {}

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC FACTORY METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start building a JSON object.
     *
     * @return a new, empty {@link ObjectBuilder}
     */
    public static ObjectBuilder object() {
        return new ObjectBuilder();
    }

    /**
     * Start building a JSON array.
     *
     * @return a new, empty {@link ArrayBuilder}
     */
    public static ArrayBuilder array() {
        return new ArrayBuilder();
    }

    /**
     * Convenience factory: build a two-field error JSON object.
     * <p>
     * Result format: {@code {"error":"<message>","code":<code>}}
     *
     * @param message human-readable error description
     * @param code    numeric error code (typically an HTTP status code)
     * @return compact JSON error string
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

    /**
     * Fluent builder for a JSON object ({@code {...}}).
     * <p>
     * Key insertion order is preserved. Calling {@link #build()} serialises
     * the accumulated key/value pairs to a compact (single-line) JSON string.
     * Use {@link #buildPretty()} for an indented (2-space) representation.
     * <p>
     * All {@code put*} methods return {@code this} to support method chaining.
     */
    public static final class ObjectBuilder {

        /** Ordered list of field names. */
        private final List<String> keys = new ArrayList<>();

        /** Ordered list of already-serialised raw JSON value fragments. */
        private final List<String> values = new ArrayList<>();

        /** Private — obtain instances via {@link SimpleJson#object()}. */
        private ObjectBuilder() {}

        /**
         * Add a string field.
         * A {@code null} value is serialised as JSON {@code null}.
         *
         * @param key   field name
         * @param value string value, or {@code null}
         * @return this builder
         */
        public ObjectBuilder put(String key, String value) {
            keys.add(key);
            values.add(value == null
                    ? "null"
                    : "\"" + SimpleJson.escape(value) + "\"");
            return this;
        }

        /**
         * Add a boolean field.
         *
         * @param key   field name
         * @param value boolean value
         * @return this builder
         */
        public ObjectBuilder put(String key, boolean value) {
            keys.add(key);
            values.add(value ? "true" : "false");
            return this;
        }

        /**
         * Add an integer field.
         *
         * @param key   field name
         * @param value integer value
         * @return this builder
         */
        public ObjectBuilder put(String key, int value) {
            keys.add(key);
            values.add(Integer.toString(value));
            return this;
        }

        /**
         * Add a long field.
         *
         * @param key   field name
         * @param value long value
         * @return this builder
         */
        public ObjectBuilder put(String key, long value) {
            keys.add(key);
            values.add(Long.toString(value));
            return this;
        }

        /**
         * Add a double field.
         *
         * @param key   field name
         * @param value double value
         * @return this builder
         */
        public ObjectBuilder put(String key, double value) {
            keys.add(key);
            values.add(Double.toString(value));
            return this;
        }

        /**
         * Add an explicit JSON {@code null} field.
         *
         * @param key field name
         * @return this builder
         */
        public ObjectBuilder putNull(String key) {
            keys.add(key);
            values.add("null");
            return this;
        }

        /**
         * Embed a nested JSON array built with an {@link ArrayBuilder}.
         *
         * @param key field name
         * @param arr the nested array builder
         * @return this builder
         */
        public ObjectBuilder putArray(String key, ArrayBuilder arr) {
            keys.add(key);
            values.add(arr.build());
            return this;
        }

        /**
         * Embed a nested JSON object built with another {@link ObjectBuilder}.
         *
         * @param key field name
         * @param obj the nested object builder
         * @return this builder
         */
        public ObjectBuilder putObject(String key, ObjectBuilder obj) {
            keys.add(key);
            values.add(obj.build());
            return this;
        }

        /**
         * Embed a raw JSON fragment (object or array that was already
         * serialised externally).
         * <p>
         * <b>Caller's responsibility:</b> {@code rawJson} must be a valid JSON
         * value; no escaping or validation is performed.
         *
         * @param key     field name
         * @param rawJson pre-serialised JSON value, or {@code null}
         * @return this builder
         */
        public ObjectBuilder putRaw(String key, String rawJson) {
            keys.add(key);
            values.add(rawJson == null ? "null" : rawJson);
            return this;
        }

        /**
         * Serialise all accumulated fields to a compact (single-line) JSON
         * object string.
         *
         * @return compact JSON object string, e.g. {@code {"a":1,"b":"x"}}
         */
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

        /**
         * Serialise all accumulated fields to a pretty-printed (2-space
         * indent) JSON object string.
         *
         * @return indented JSON object string
         */
        public String buildPretty() {
            return SimpleJson.prettyPrint(build(), 2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ARRAY BUILDER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fluent builder for a JSON array ({@code [...]}).
     * <p>
     * Elements are appended in order. Use {@link #build()} for a compact
     * representation or {@link #buildPretty()} for an indented one.
     * All {@code add*} methods return {@code this} for chaining.
     */
    public static final class ArrayBuilder {

        /** Already-serialised raw JSON element fragments, in insertion order. */
        private final List<String> elements = new ArrayList<>();

        /** Private — obtain instances via {@link SimpleJson#array()}. */
        private ArrayBuilder() {}

        /**
         * Append a string element.
         * A {@code null} value is serialised as JSON {@code null}.
         *
         * @param value string value, or {@code null}
         * @return this builder
         */
        public ArrayBuilder add(String value) {
            elements.add(value == null
                    ? "null"
                    : "\"" + SimpleJson.escape(value) + "\"");
            return this;
        }

        /**
         * Append a boolean element.
         *
         * @param value boolean value
         * @return this builder
         */
        public ArrayBuilder add(boolean value) {
            elements.add(value ? "true" : "false");
            return this;
        }

        /**
         * Append an integer element.
         *
         * @param value integer value
         * @return this builder
         */
        public ArrayBuilder add(int value) {
            elements.add(Integer.toString(value));
            return this;
        }

        /**
         * Append a long element.
         *
         * @param value long value
         * @return this builder
         */
        public ArrayBuilder add(long value) {
            elements.add(Long.toString(value));
            return this;
        }

        /**
         * Append an explicit JSON {@code null} element.
         *
         * @return this builder
         */
        public ArrayBuilder addNull() {
            elements.add("null");
            return this;
        }

        /**
         * Append a nested JSON object built with an {@link ObjectBuilder}.
         *
         * @param obj the nested object builder
         * @return this builder
         */
        public ArrayBuilder addObject(ObjectBuilder obj) {
            elements.add(obj.build());
            return this;
        }

        /**
         * Append a nested JSON array built with another {@link ArrayBuilder}.
         *
         * @param arr the nested array builder
         * @return this builder
         */
        public ArrayBuilder addArray(ArrayBuilder arr) {
            elements.add(arr.build());
            return this;
        }

        /**
         * Append a raw JSON fragment.
         * <p>
         * <b>Caller's responsibility:</b> {@code rawJson} must be a valid JSON
         * value; no validation is performed.
         *
         * @param rawJson pre-serialised JSON value, or {@code null}
         * @return this builder
         */
        public ArrayBuilder addRaw(String rawJson) {
            elements.add(rawJson == null ? "null" : rawJson);
            return this;
        }

        /**
         * Serialise all accumulated elements to a compact (single-line) JSON
         * array string.
         *
         * @return compact JSON array string, e.g. {@code [1,"a",true]}
         */
        public String build() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(elements.get(i));
            }
            sb.append(']');
            return sb.toString();
        }

        /**
         * Serialise all accumulated elements to a pretty-printed (2-space
         * indent) JSON array string.
         *
         * @return indented JSON array string
         */
        public String buildPretty() {
            return SimpleJson.prettyPrint(build(), 2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MINIMAL PARSER
    //
    //  Supported return types:
    //    Map<String,Object>  — JSON object  { ... }
    //    List<Object>        — JSON array   [ ... ]
    //    String              — JSON string  "..."
    //    Double              — JSON number
    //    Boolean             — true / false
    //    null                — null
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parse a JSON object string into a {@code Map<String, Object>}.
     * <p>
     * Values in the returned map may be:
     * {@link String}, {@link Double}, {@link Boolean}, {@code null},
     * {@code Map<String,Object>}, or {@code List<Object>}.
     *
     * @param json the JSON text (must begin with {@code '{'})
     * @return a {@link LinkedHashMap} preserving key insertion order
     * @throws IllegalArgumentException if the input is null, blank,
     *                                  or does not represent a JSON object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON input is null or blank.");
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
     * <p>
     * Elements in the returned list may be:
     * {@link String}, {@link Double}, {@link Boolean}, {@code null},
     * {@code Map<String,Object>}, or {@code List<Object>}.
     *
     * @param json the JSON text (must begin with {@code '['})
     * @return a {@link List} of parsed values
     * @throws IllegalArgumentException if the input is null, blank,
     *                                  or does not represent a JSON array
     */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON input is null or blank.");
        }
        Object result = new Parser(json.trim()).parseValue();
        if (!(result instanceof List)) {
            throw new IllegalArgumentException(
                    "Expected a JSON array '[...]' but got: " + json);
        }
        return (List<Object>) result;
    }

    // ── Internal recursive-descent parser ──────────────────────────────────

    /**
     * Internal recursive-descent parser.
     * <p>
     * A new {@code Parser} instance is created for each top-level parse call;
     * it is not thread-safe but does not need to be because it is never shared.
     */
    private static final class Parser {

        /** The source JSON string being parsed. */
        private final String src;

        /** Current read position within {@link #src}. */
        private int pos;

        /**
         * Constructs a parser for the given source string.
         *
         * @param src JSON source string (must not be {@code null})
         */
        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        /**
         * Parse and return the next JSON value starting at the current position.
         *
         * @return parsed Java object
         * @throws IllegalArgumentException on unexpected character or EOF
         */
        Object parseValue() {
            skipWs();
            if (pos >= src.length()) {
                throw err("Unexpected end of input");
            }
            char ch = src.charAt(pos);
            if (ch == '{')                           return parseJsonObject();
            if (ch == '[')                           return parseJsonArray();
            if (ch == '"')                           return parseString();
            if (ch == 't')                           return parseLiteral("true",  Boolean.TRUE);
            if (ch == 'f')                           return parseLiteral("false", Boolean.FALSE);
            if (ch == 'n')                           return parseLiteral("null",  null);
            if (ch == '-' || Character.isDigit(ch))  return parseNumber();
            throw err("Unexpected character: '" + ch + "'");
        }

        /**
         * Parse a JSON object ({@code {...}}) and return it as a
         * {@link LinkedHashMap}.
         */
        private Map<String, Object> parseJsonObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; } // empty object

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
                if (next == '}') { pos++; break; }     // end of object
                if (next == ',') { pos++; continue; }   // next member
                throw err("Expected ',' or '}' in object, got '" + next + "'");
            }
            return map;
        }

        /**
         * Parse a JSON array ({@code [...]}) and return it as a {@link List}.
         */
        private List<Object> parseJsonArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; } // empty array

            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();

                char next = peek();
                if (next == ']') { pos++; break; }     // end of array
                if (next == ',') { pos++; continue; }   // next element
                throw err("Expected ',' or ']' in array, got '" + next + "'");
            }
            return list;
        }

        /**
         * Parse a JSON string value (the surrounding double-quotes are
         * consumed; escape sequences are decoded).
         *
         * @return the decoded string content
         */
        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();

            while (pos < src.length()) {
                char ch = src.charAt(pos++);

                if (ch == '"') {
                    // Closing quote — string is complete
                    return sb.toString();
                }

                if (ch == '\\') {
                    // Escape sequence
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
                            // 4-hex-digit Unicode escape \\uXXXX
                            if (pos + 4 > src.length()) {
                                throw err("Truncated \\u escape sequence");
                            }
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        }
                        default:
                            // Unknown escape — keep the character as-is
                            sb.append(esc);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw err("Unterminated string literal");
        }

        /**
         * Parse a JSON number (integer or floating-point, with optional
         * exponent) and return it as a {@link Double}.
         *
         * @return parsed numeric value
         */
        private Double parseNumber() {
            int start = pos;

            // Optional leading minus
            if (pos < src.length() && src.charAt(pos) == '-') pos++;

            // Integer digits
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;

            // Optional fractional part
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }

            // Optional exponent
            if (pos < src.length()
                    && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length()
                        && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }

            return Double.parseDouble(src.substring(start, pos));
        }

        /**
         * Consume the exact literal string {@code literal} starting at the
         * current position and return {@code value}.
         *
         * @param literal expected literal text (e.g. {@code "true"})
         * @param value   Java value to return on success
         * @return {@code value}
         * @throws IllegalArgumentException if the literal is not found
         */
        private Object parseLiteral(String literal, Object value) {
            if (src.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw err("Expected literal '" + literal + "'");
        }

        /** Advance {@link #pos} past any ASCII whitespace characters. */
        private void skipWs() {
            while (pos < src.length() && src.charAt(pos) <= ' ') pos++;
        }

        /**
         * Return the character at the current position without consuming it,
         * or {@code 0} if the end of input has been reached.
         *
         * @return current character or {@code 0}
         */
        private char peek() {
            return (pos < src.length()) ? src.charAt(pos) : 0;
        }

        /**
         * Assert that the character at the current position equals {@code ch}
         * and advance past it.
         *
         * @param ch expected character
         * @throws IllegalArgumentException if the character does not match
         */
        private void expect(char ch) {
            if (pos >= src.length() || src.charAt(pos) != ch) {
                String found = (pos < src.length())
                        ? String.valueOf(src.charAt(pos))
                        : "EOF";
                throw err("Expected '" + ch + "' but found '" + found + "'");
            }
            pos++;
        }

        /**
         * Build an {@link IllegalArgumentException} that includes the current
         * position and a short excerpt of the surrounding source text for
         * easier debugging.
         *
         * @param msg human-readable error description
         * @return the exception (not thrown here — caller decides)
         */
        private IllegalArgumentException err(String msg) {
            int end = Math.min(pos + 30, src.length());
            return new IllegalArgumentException(
                    msg + " at position " + pos
                    + " near: [" + src.substring(pos, end) + "]");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SHARED STATIC UTILITIES
    //  Declared on SimpleJson (not on the inner classes) so that both
    //  ObjectBuilder and ArrayBuilder can call them as SimpleJson.xxx(...)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Escape a raw Java string for safe embedding inside JSON double-quotes.
     * <p>
     * Characters handled:
     * {@code "}, {@code \}, {@code \b}, {@code \f}, {@code \n},
     * {@code \r}, {@code \t}, and control characters ({@code < 0x20}).
     *
     * @param s the string to escape; {@code null} returns {@code ""}
     * @return JSON-safe escaped string (without surrounding quotes)
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
                        // Encode remaining control characters as \\uXXXX
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Re-format a compact JSON string with human-readable indentation.
     * <p>
     * The formatter correctly avoids splitting inside string literals (it
     * tracks whether the current character is inside a quoted string).
     * <p>
     * <b>Note:</b> the simple escaped-quote detection used here
     * ({@code prev != '\\'}) does not handle the edge case of a double
     * backslash immediately before a quote ({@code \\"}); for the server's
     * own output this is not an issue because all strings are produced by
     * {@link #escape(String)}.
     *
     * @param compact    compact (single-line) JSON string
     * @param indentSize number of spaces per indent level (typically 2 or 4)
     * @return pretty-printed JSON string, or {@code "null"} if input is null
     */
    public static String prettyPrint(String compact, int indentSize) {
        if (compact == null) return "null";

        StringBuilder sb    = new StringBuilder(compact.length() * 2);
        int           depth = 0;
        boolean       inStr = false; // true while inside a JSON string literal

        for (int i = 0; i < compact.length(); i++) {
            char ch   = compact.charAt(i);
            char prev = (i > 0) ? compact.charAt(i - 1) : 0;

            // Track string boundaries, respecting simple backslash escapes
            if (ch == '"' && prev != '\\') {
                inStr = !inStr;
                sb.append(ch);
                continue;
            }

            // Inside a string — emit characters verbatim
            if (inStr) {
                sb.append(ch);
                continue;
            }

            // Structural characters — add newlines and indentation
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
                    // Add a space after the colon for readability
                    sb.append(": ");
                    break;

                default:
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Append {@code depth * indentSize} space characters to {@code sb}.
     *
     * @param sb         target string builder
     * @param depth      current nesting depth
     * @param indentSize spaces per level
     */
    private static void appendIndent(StringBuilder sb, int depth, int indentSize) {
        for (int i = 0; i < depth * indentSize; i++) {
            sb.append(' ');
        }
    }
}