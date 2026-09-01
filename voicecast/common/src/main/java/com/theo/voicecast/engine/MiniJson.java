package com.theo.voicecast.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the small subset Vosk returns:
 * <pre>
 *   { "text": "ignis" }
 *   { "partial": "ig..." }
 *   { "alternatives": [ { "text": "...", "confidence": 0.9 }, ... ] }
 * </pre>
 *
 * Intentionally avoids pulling in Gson/Jackson so VoiceCast stays light and
 * classpath-friendly when relocated.
 */
final class MiniJson {
    private MiniJson() {}

    static String getString(String json, String key) {
        if (json == null) return "";
        String marker = "\"" + key + "\"";
        int i = json.indexOf(marker);
        if (i < 0) return "";
        int colon = json.indexOf(':', i + marker.length());
        if (colon < 0) return "";
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart < 0) return "";
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int j = quoteStart + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (esc) {
                sb.append(switch (c) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    default -> c;
                });
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static float getFloat(String json, String key) {
        if (json == null) return 0f;
        String marker = "\"" + key + "\"";
        int i = json.indexOf(marker);
        if (i < 0) return 0f;
        int colon = json.indexOf(':', i + marker.length());
        if (colon < 0) return 0f;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        int start = j;
        while (j < json.length() && "-+0123456789.eE".indexOf(json.charAt(j)) >= 0) j++;
        if (j == start) return 0f;
        try {
            return Float.parseFloat(json.substring(start, j));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** Extracts {@code { "text": ..., "confidence": ... }} objects from an alternatives array. */
    static List<Alt> getAlternatives(String json) {
        List<Alt> result = new ArrayList<>();
        if (json == null) return result;
        int arrStart = json.indexOf('[');
        int arrEnd = arrStart < 0 ? -1 : json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return result;
        int depth = 0;
        int objStart = -1;
        for (int i = arrStart; i <= arrEnd; i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    String text = getString(obj, "text");
                    float conf = getFloat(obj, "confidence");
                    if (!text.isBlank()) result.add(new Alt(text, conf));
                    objStart = -1;
                }
            }
        }
        return result;
    }

    record Alt(String text, float confidence) {}

    /**
     * Parse a flat {@code {"string key": int, ...}} object such as a model's
     * {@code vocab.json}. Handles basic escape sequences in keys.
     */
    static Map<String, Integer> parseStringIntObject(String json) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (json == null) return map;
        int i = 0;
        int n = json.length();
        while (i < n) {
            // find next quoted key
            while (i < n && json.charAt(i) != '"') i++;
            if (i >= n) break;
            StringBuilder key = new StringBuilder();
            boolean esc = false;
            i++; // skip opening quote
            while (i < n) {
                char c = json.charAt(i++);
                if (esc) {
                    key.append(switch (c) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'u' -> '?'; // surrogate phoneme tokens are unlikely in vocab
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        default -> c;
                    });
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    break;
                } else {
                    key.append(c);
                }
            }
            // find colon
            while (i < n && json.charAt(i) != ':') i++;
            i++;
            // parse integer
            while (i < n && !Character.isDigit(json.charAt(i)) && json.charAt(i) != '-') i++;
            int start = i;
            while (i < n && "-0123456789".indexOf(json.charAt(i)) >= 0) i++;
            if (i > start) {
                try {
                    map.put(key.toString(), Integer.parseInt(json.substring(start, i)));
                } catch (NumberFormatException ignored) {}
            }
        }
        return map;
    }
}
