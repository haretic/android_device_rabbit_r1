// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small strict JSON codec with bounded input and container nesting. */
public final class Json {
    private static final int MAX_LENGTH = 65_536;
    private static final int MAX_DEPTH = 16;
    private Json() { }

    public static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        string(out, text);
        return out.toString();
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32 || Character.isSurrogate(c)) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append("0123456789abcdef".charAt(c >> shift & 15));
                        }
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    public static Map<String, Object> parse(String json) {
        if (json == null || json.length() > MAX_LENGTH
                || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LENGTH) {
            throw new IllegalArgumentException("JSON too large");
        }
        Parser parser = new Parser(json);
        parser.space();
        if (!parser.at('{')) {
            throw new IllegalArgumentException("Expected object");
        }
        Map<String, Object> map = parser.object(1);
        parser.space();
        if (parser.pos != json.length()) {
            throw new IllegalArgumentException("Trailing JSON");
        }
        return map;
    }

    public static String write(Map<String, ?> map) {
        if (map == null) {
            throw new IllegalArgumentException("Expected object");
        }
        StringBuilder out = new StringBuilder();
        value(out, map, 0);
        return out.toString();
    }

    private static void value(StringBuilder out, Object item, int depth) {
        if (item == null) {
            out.append("null");
        } else if (item instanceof String) {
            string(out, (String) item);
        } else if (item instanceof Boolean) {
            out.append(item);
        } else if (item instanceof Number) {
            String number = item.toString();
            Parser parser = new Parser(number);
            parser.number();
            if (parser.pos != number.length()) {
                throw new IllegalArgumentException("Invalid number");
            }
            out.append(number);
        } else if (item instanceof Map<?, ?>) {
            if (depth >= MAX_DEPTH) {
                throw new IllegalArgumentException("JSON too deep");
            }
            out.append('{');
            boolean comma = false;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("Invalid key");
                }
                if (comma) {
                    out.append(',');
                }
                comma = true;
                string(out, (String) entry.getKey());
                out.append(':');
                value(out, entry.getValue(), depth + 1);
            }
            out.append('}');
        } else if (item instanceof List<?>) {
            if (depth >= MAX_DEPTH) {
                throw new IllegalArgumentException("JSON too deep");
            }
            out.append('[');
            boolean comma = false;
            for (Object child : (List<?>) item) {
                if (comma) {
                    out.append(',');
                }
                comma = true;
                value(out, child, depth + 1);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value");
        }
    }

    /** Cursor parsing avoids regex backtracking and caps recursive work. */
    private static final class Parser {
        private final String text;
        private int pos;
        Parser(String text) { this.text = text; }

        private IllegalArgumentException bad() {
            return new IllegalArgumentException("Malformed JSON");
        }

        void space() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                    break;
                }
                pos++;
            }
        }

        boolean at(char c) { return pos < text.length() && text.charAt(pos) == c; }

        void take(char c) {
            if (!at(c)) {
                throw bad();
            }
            pos++;
        }

        Object value(int depth) {
            space();
            if (pos == text.length()) {
                throw bad();
            }
            switch (text.charAt(pos)) {
                case '{': return object(depth + 1);
                case '[': return array(depth + 1);
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default: return number();
            }
        }

        Map<String, Object> object(int depth) {
            if (depth > MAX_DEPTH) {
                throw bad();
            }
            take('{');
            space();
            Map<String, Object> map = new LinkedHashMap<>();
            if (at('}')) {
                pos++;
                return map;
            }
            while (true) {
                String key = string();
                space();
                take(':');
                Object item = value(depth);
                if (map.containsKey(key)) {
                    throw bad();
                }
                map.put(key, item);
                space();
                if (at('}')) {
                    pos++;
                    return map;
                }
                take(',');
                space();
            }
        }

        List<Object> array(int depth) {
            if (depth > MAX_DEPTH) {
                throw bad();
            }
            take('[');
            space();
            List<Object> list = new ArrayList<>();
            if (at(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(value(depth));
                space();
                if (at(']')) {
                    pos++;
                    return list;
                }
                take(',');
            }
        }

        String string() {
            take('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 32) {
                    throw bad();
                }
                if (c == '\\') {
                    if (pos == text.length()) {
                        throw bad();
                    }
                    c = text.charAt(pos++);
                    switch (c) {
                        case '"': case '\\': case '/': out.append(c); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                if (pos == text.length()) {
                                    throw bad();
                                }
                                char hex = text.charAt(pos++);
                                int digit = hex < 128 ? Character.digit(hex, 16) : -1;
                                if (digit < 0) {
                                    throw bad();
                                }
                                code = code * 16 + digit;
                            }
                            out.append((char) code);
                            break;
                        default: throw bad();
                    }
                } else {
                    out.append(c);
                }
            }
            throw bad();
        }

        void literal(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw bad();
            }
            pos += literal.length();
        }

        Double number() {
            int start = pos;
            if (at('-')) {
                pos++;
            }
            if (at('0')) {
                pos++;
            } else {
                if (pos == text.length() || text.charAt(pos) < '1' || text.charAt(pos) > '9') {
                    throw bad();
                }
                digits();
            }
            if (at('.')) {
                pos++;
                digits();
            }
            if (at('e') || at('E')) {
                pos++;
                if (at('+') || at('-')) {
                    pos++;
                }
                digits();
            }
            try {
                double number = Double.parseDouble(text.substring(start, pos));
                if (!Double.isFinite(number)) {
                    throw bad();
                }
                return number;
            } catch (NumberFormatException e) {
                throw bad();
            }
        }

        private void digits() {
            int start = pos;
            while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == start) {
                throw bad();
            }
        }
    }
}
