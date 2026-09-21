package com.theo.voicecast.match;

import com.theo.voicecast.api.SessionVocabulary;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Chant-line id shapes of the vocabulary (semantic contract v2): the id
 * scheme {@code <spell>.chant.<lang>.<v>:<i>} (language-keyed) or
 * {@code <spell>.chant.<v>:<i>} (legacy) is a shared vocabulary convention —
 * the adjudicator uses it to derive which entries are trigger rows, which
 * are first lines and which are last lines (middle lines carry no idle
 * meaning, mirroring the pre-v2 ChantGate rule).
 */
public final class LineShapes {
    public record LineId(String spellId, String lang, int variant, int index) {}

    private LineShapes() {}

    /** Parse a vocabulary id; null for trigger rows (no {@code .chant.}). */
    public static LineId parse(String id) {
        if (id == null) return null;
        int marker = id.indexOf(".chant.");
        if (marker < 0) return null;
        String rest = id.substring(marker + 7);
        // rest = [lang '.'] v ':' i
        String lang = "";
        int colon = rest.indexOf(':');
        if (colon < 0) return null;
        String head = rest.substring(0, colon);
        String tail = rest.substring(colon + 1);
        int dot = head.indexOf('.');
        if (dot >= 0) {
            lang = head.substring(0, dot);
            head = head.substring(dot + 1);
        }
        try {
            int variant = Integer.parseInt(head.trim());
            int index = Integer.parseInt(tail.trim());
            return new LineId(id.substring(0, marker), lang, variant, index);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether the entry is a first line ({@code :0}) of its chant group. */
    public static boolean isFirstLine(SessionVocabulary.Entry e) {
        LineId line = parse(e.id());
        return line != null && line.index() == 0;
    }

    /** Whether the entry is a chant line that is neither first nor last in
     *  its group (idle utterances carry no meaning for it). */
    public static boolean isMiddleLine(SessionVocabulary.Entry e, Collection<SessionVocabulary.Entry> vocabulary) {
        LineId line = parse(e.id());
        if (line == null) return false;
        int max = maxLineIndex(vocabulary, line.spellId(), line.lang(), line.variant());
        return line.index() != 0 && line.index() != max;
    }

    /** Highest line index of one chant group ({@code <v>}); -1 when unknown. */
    public static int maxLineIndex(Collection<SessionVocabulary.Entry> vocabulary, String spellId,
                                   String lang, int variant) {
        int max = -1;
        for (SessionVocabulary.Entry e : vocabulary) {
            LineId l = parse(e.id());
            if (l == null) continue;
            if (l.spellId().equals(spellId) && l.variant() == variant
                    && l.lang().equalsIgnoreCase(lang)) {
                max = Math.max(max, l.index());
            }
        }
        return max;
    }

    /** Whether this entry's language buckets intersect the enabled set
     *  (empty = all enabled; bucket-less = always). */
    public static boolean languageEnabled(SessionVocabulary.Entry e, java.util.Set<String> enabled) {
        if (e.languages().isEmpty() || enabled.isEmpty()) return true;
        for (String lang : e.languages().keySet()) {
            if (enabled.contains(lang.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
