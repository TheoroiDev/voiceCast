package com.theo.voicecast.match;

import java.util.List;
import java.util.Locale;

/**
 * The lenient first-line rules (loose text + loose phonetic), moved verbatim
 * from WizardReal's {@code ChantEngine} idle L1 gate (D9) into the voicecast
 * adjudicator (semantic contract v2, C1b): matching consolidates into
 * voicecast; WizardReal keeps an identical copy for the CHANT PROGRESS gate
 * (mid-chant line feeding stays gameplay-side). The two copies must stay
 * semantically identical — the equivalence vectors pin them.
 *
 * <p>Acceptances are EXACT-tier candidates: the pre-v2 L1 gate outranked
 * every non-ENTER result (首行即门), so any of its hits wins the EXACT tier.
 */
public final class LenientLine {
    private LenientLine() {}

    /** Text half of the lenient first-line rules over the line's aliases. */
    public static boolean looseTextRulesHit(List<String> aliases, String heard) {
        if (heard == null || heard.isBlank()) return false;
        String h = heard.toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            if (looseText(h, alias.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Lenient per-line match: IPA phonemes first, then text aliases. */
    public static boolean lineMatches(List<String> templateIpa, List<String> aliases,
                                      String heard, List<String> heardIpa) {
        if (heardIpa != null && !heardIpa.isEmpty() && templateIpa != null && !templateIpa.isEmpty()) {
            String ipaText = String.join(" ", heardIpa);
            for (String templ : templateIpa) {
                if (loosePhonetic(ipaText, templ)) return true;
            }
        }
        if (heard != null && !heard.isBlank()) {
            for (String alias : aliases) {
                if (looseText(heard.toLowerCase(Locale.ROOT),
                        alias.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    public static boolean looseText(String heard, String alias) {
        String h = normalize(heard);
        String a = normalize(alias);
        if (h.isEmpty() || a.isEmpty()) return false;
        // The whole line spoken (filler words around it are fine).
        if (h.contains(a)) return true;
        // Token coverage: most of the line's words must appear in the
        // utterance. This deliberately REPLACED the old `alias.contains(heard)`
        // substring rule, which matched a line on its first word alone (any
        // substring of the alias counted as a full line).
        String[] tokens = a.split(" ");
        if (tokens.length >= 2) {
            int hit = 0;
            for (String token : tokens) {
                if (!token.isEmpty() && h.contains(token)) hit++;
            }
            return (double) hit / tokens.length >= 0.75;
        }
        // Single-word lines: fuzzy full-string match.
        int dist = Levenshtein.distance(h, a);
        return 1.0 - (double) dist / Math.max(1, Math.max(h.length(), a.length())) >= 0.6;
    }

    /** Lowercase, strip punctuation, collapse whitespace — chant aliases and
     *  recognizer transcripts otherwise differ by trailing "!" etc. */
    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Very lenient phonetic: normalized template's letters mostly appear in order. */
    public static boolean loosePhonetic(String heardIpa, String template) {
        String h = heardIpa.replaceAll("[\\sˈˌː.]", "").toLowerCase(Locale.ROOT);
        String t = template.replaceAll("[\\sˈˌː.]", "").toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return false;
        if (h.contains(t)) return true;
        // Levenshtein-ish ratio on chars
        int dist = Levenshtein.distance(h, t);
        return 1.0 - (double) dist / Math.max(1, Math.max(h.length(), t.length())) >= 0.6;
    }
}
