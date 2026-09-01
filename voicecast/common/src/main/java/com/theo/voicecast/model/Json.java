package com.theo.voicecast.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON parser/writer for {@code config/voicecast/models.json}.
 * Same philosophy as {@code config/Toml}: a tiny hand-rolled reader so the shaded
 * common jar stays light and cannot clash with any JSON library the platform ships.
 *
 * <p>Values map to Java types: String, Double, Long, Boolean, null, List&lt;Object&gt;,
 * Map&lt;String,Object&gt; (LinkedHashMap, insertion order preserved).
 */
public final class Json {
    private Json() {}

    // ---------------- parsing ----------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.atEnd()) throw p.error("trailing content");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s == null ? "" : s; }

        boolean atEnd() { return i >= s.length(); }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON error at offset " + i + ": " + msg);
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            if (atEnd()) throw error("unexpected end");
            return s.charAt(i);
        }

        void expect(char c) {
            skipWs();
            if (atEnd() || s.charAt(i) != c) throw error("expected '" + c + "'");
            i++;
        }

        Object readValue() {
            skipWs();
            if (atEnd()) throw error("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expectWord("true"); return Boolean.TRUE;
                case 'f': expectWord("false"); return Boolean.FALSE;
                case 'n': expectWord("null"); return null;
                default: return readNumber();
            }
        }

        private void expectWord(String w) {
            if (!s.startsWith(w, i)) throw error("invalid literal");
            i += w.length();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (!atEnd() && peek() == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                expect(':');
                Object v = readValue();
                map.put(key, v);
                skipWs();
                if (atEnd()) throw error("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return map;
                if (c != ',') throw error("expected ',' or '}' in object");
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (!atEnd() && peek() == ']') { i++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                if (atEnd()) throw error("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return list;
                if (c != ',') throw error("expected ',' or ']' in array");
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw error("unterminated escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw error("bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object readNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (i == start) throw error("invalid value");
            String num = s.substring(start, i);
            try {
                if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                    return Long.parseLong(num);
                }
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw error("invalid number '" + num + "'");
            }
        }
    }

    // ---------------- writing ----------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Number n) { sb.append(formatNumber(n)); return; }
        if (v instanceof Map<?, ?> m) { writeObject(sb, m, indent); return; }
        if (v instanceof List<?> l) { writeArray(sb, l, indent); return; }
        writeString(sb, String.valueOf(v));
    }

    private static String formatNumber(Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return n.longValue() + "";
        }
        double d = n.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0E15) {
            return (long) d + "";
        }
        return Double.toString(d);
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m, int indent) {
        if (m.isEmpty()) { sb.append("{}"); return; }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++i < m.size()) sb.append(',');
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> l, int indent) {
        if (l.isEmpty()) { sb.append("[]"); return; }
        boolean simple = l.stream().allMatch(x -> x instanceof String || x instanceof Number || x instanceof Boolean);
        if (simple) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                writeValue(sb, l.get(i), indent + 1);
            }
            sb.append(']');
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < l.size(); i++) {
            indent(sb, indent + 1);
            writeValue(sb, l.get(i), indent + 1);
            if (i < l.size() - 1) sb.append(',');
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("    ");
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------- typed accessors ----------------

    public static String getString(Map<String, Object> map, String key, String dflt) {
        Object v = map.get(key);
        return v instanceof String s ? s : dflt;
    }

    public static boolean getBool(Map<String, Object> map, String key, boolean dflt) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : dflt;
    }

    public static long getLong(Map<String, Object> map, String key, long dflt) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        return dflt;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    public static List<String> getStringList(Map<String, Object> map, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : getList(map, key)) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }
}
