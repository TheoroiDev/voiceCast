package com.theo.voicecast.config;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny, dependency-free TOML reader/writer for VoiceCast config files.
 *
 * <p>Supports the subset we need:
 * <ul>
 *   <li>one level of {@code [section]} tables (incl. dotted keys for writes),</li>
 *   <li>scalar values: strings (quoted), booleans, integers, floats,</li>
 *   <li>inline arrays of strings: {@code ["a", "b"]},</li>
 *   <li>line comments ({@code #}) and blank lines (ignored on read).</li>
 * </ul>
 *
 * <p>Purposefully avoids a full TOML library so the shaded common jar stays
 * dependency-light and cannot clash with the TOML parser Forge already ships.
 */
public final class Toml {
    /** table -> (key -> value). Values are String/Long/Double/Boolean/List&lt;String&gt;. */
    private final Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
    private final List<String> headerComments = new ArrayList<>();

    public Toml() {}

    public Toml setComment(String comment) {
        headerComments.add(comment);
        return this;
    }

    private Map<String, Object> section(String name) {
        return sections.computeIfAbsent(name, k -> new LinkedHashMap<>());
    }

    public Toml setString(String section, String key, String value) {
        section(section).put(key, value == null ? "" : value);
        return this;
    }

    public Toml setBool(String section, String key, boolean value) {
        section(section).put(key, value);
        return this;
    }

    public Toml setInt(String section, String key, long value) {
        section(section).put(key, value);
        return this;
    }

    public Toml setDouble(String section, String key, double value) {
        section(section).put(key, value);
        return this;
    }

    public Toml setStringList(String section, String key, List<String> values) {
        section(section).put(key, List.copyOf(values));
        return this;
    }

    public String getString(String section, String key, String dflt) {
        Object v = section(section).get(key);
        return v instanceof String s ? s : dflt;
    }

    public boolean getBool(String section, String key, boolean dflt) {
        Object v = section(section).get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return dflt;
    }

    public long getInt(String section, String key, long dflt) {
        Object v = section(section).get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
        }
        return dflt;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String section, String key, List<String> dflt) {
        Object v = section(section).get(key);
        return v instanceof List ? (List<String>) v : dflt;
    }

    public boolean has(String section, String key) {
        return sections.getOrDefault(section, Map.of()).containsKey(key);
    }

    // ---- IO --------------------------------------------------------------

    public static Toml load(Path file) {
        Toml toml = new Toml();
        if (!Files.isRegularFile(file)) return toml;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String current = "";
            for (String raw : lines) {
                String line = stripComment(raw).trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                toml.section(current).put(key, parseValue(val));
            }
        } catch (IOException e) {
            VoiceCast.LOGGER.warn("Failed to read TOML config {}", file, e);
        }
        return toml;
    }

    public void save(Path file) {
        StringBuilder sb = new StringBuilder();
        for (String c : headerComments) sb.append("# ").append(c).append('\n');
        if (!headerComments.isEmpty()) sb.append('\n');
        for (Map.Entry<String, Map<String, Object>> sec : sections.entrySet()) {
            if (!sec.getKey().isEmpty()) sb.append('[').append(sec.getKey()).append("]\n");
            for (Map.Entry<String, Object> e : sec.getValue().entrySet()) {
                sb.append(e.getKey()).append(" = ").append(render(e.getValue())).append('\n');
            }
            sb.append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            VoiceCast.LOGGER.warn("Failed to write TOML config {}", file, e);
        }
    }

    // ---- parsing helpers -------------------------------------------------

    private static String stripComment(String line) {
        boolean inStr = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inStr) {
                if (c == quote && line.charAt(i - 1) != '\\') inStr = false;
            } else if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static Object parseValue(String val) {
        if (val.isEmpty()) return "";
        char first = val.charAt(0);
        if (first == '"' || first == '\'') {
            return parseQuoted(val);
        }
        if (val.startsWith("[")) {
            List<String> out = new ArrayList<>();
            String inner = val.substring(val.indexOf('[') + 1, val.lastIndexOf(']') >= 0 ? val.lastIndexOf(']') : val.length());
            for (String part : inner.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(unquote(t));
            }
            return out;
        }
        String low = val.toLowerCase();
        if (low.equals("true")) return Boolean.TRUE;
        if (low.equals("false")) return Boolean.FALSE;
        try { return Long.parseLong(val); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(val); } catch (NumberFormatException ignored) {}
        return val; // bare string
    }

    private static String parseQuoted(String val) {
        int end = findClosingQuote(val, val.charAt(0));
        if (end < 0) return unquote(val);
        return unquote(val.substring(0, end + 1));
    }

    private static int findClosingQuote(String s, char q) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == q && s.charAt(i - 1) != '\\') return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("\"") || t.startsWith("'"))) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String render(Object v) {
        if (v instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('"').append(String.valueOf(list.get(i))
                        .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }
}
