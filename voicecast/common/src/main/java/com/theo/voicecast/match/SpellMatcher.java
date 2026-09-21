package com.theo.voicecast.match;

import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.ThresholdHint;

import java.util.List;
import java.util.Locale;

/**
 * Matches recognized speech text against vocabulary entry aliases
 * (semantic contract v2: moved from WizardReal's matcher chain into the
 * voicecast adjudicator, work order C1b §0.2 — decision authority lives in
 * voicecast).
 *
 * <p>Strategy (best score wins, per-entry {@link ThresholdHint#text()} or
 * the calibration default applied by the adjudicator):
 * <ol>
 *   <li>normalized alias equals normalized utterance ...... 1.0 (verbatim)</li>
 *   <li>multi-word alias appears inside the utterance ...... 0.95 (verbatim)</li>
 *   <li>single-word alias appears as a whole word .......... 0.9 (verbatim)</li>
 *   <li>Levenshtein/phonetic similarity on the full strings  0..1 (fuzzy)</li>
 * </ol>
 *
 * <p>TM-FIX tie rule carried over: score ties are broken by LONGEST alias
 * first (on sentence-length transcripts a short alias can be contained
 * inside another spell's longer alias); a full tie falls back to entry id
 * lexicographic order, smaller id first.
 *
 * <p>Verbatim hits ({@link Match#verbatim()}) are EXACT-tier candidates;
 * fuzzy hits are NEAR-tier. The rules themselves are unchanged from the
 * pre-v2 {@code SpellMatcher} — only the ownership moved.
 */
public final class SpellMatcher {

    private SpellMatcher() {}

    public record Match(String entryId, String alias, float score) {}

    /** Best verbatim and best fuzzy match over the given (trigger-surface)
     *  entries; either null when none. Split per tier: a verbatim hit must
     *  not be outshone by a slightly higher fuzzy score of another alias —
     *  the tiers differ in strength (EXACT vs NEAR). */
    public record Result(Match verbatim, Match fuzzy) {}

    public static Result match(String heard, List<SessionVocabulary.Entry> entries) {
        String text = normalize(heard);
        if (text.isEmpty() || "[unk]".equals(text)) return new Result(null, null);

        Match bestVerbatim = null;
        Match bestFuzzy = null;
        int verbatimAliasLen = -1;
        int fuzzyAliasLen = -1;
        for (SessionVocabulary.Entry entry : entries) {
            for (String alias : entry.aliases()) {
                String normAlias = normalize(alias);
                if (normAlias.isEmpty()) continue;
                boolean verbatim;
                float score;
                if (normAlias.equals(text)) {
                    score = 1.0f; verbatim = true;
                } else {
                    boolean multiWord = normAlias.indexOf(' ') >= 0;
                    if (multiWord) {
                        if (text.contains(normAlias)) { score = 0.95f; verbatim = true; }
                        else { score = fuzzy(normAlias, text); verbatim = false; }
                    } else if (containsWord(text, normAlias)) {
                        score = 0.9f; verbatim = true;
                    } else {
                        score = fuzzy(normAlias, text); verbatim = false;
                    }
                }
                if (score <= 0f) continue;
                if (verbatim) {
                    if (bestVerbatim == null || score > bestVerbatim.score()) {
                        bestVerbatim = new Match(entry.id(), normAlias, score);
                        verbatimAliasLen = normAlias.length();
                    } else if (score == bestVerbatim.score()) {
                        // Score tie: longest alias wins (most specific match);
                        // full tie -> entry id lexicographic, smaller id first
                        // (TM-FIX).
                        int len = normAlias.length();
                        if (len > verbatimAliasLen
                                || (len == verbatimAliasLen && entry.id().compareTo(bestVerbatim.entryId()) < 0)) {
                            bestVerbatim = new Match(entry.id(), normAlias, score);
                            verbatimAliasLen = len;
                        }
                    }
                } else {
                    if (bestFuzzy == null || score > bestFuzzy.score()) {
                        bestFuzzy = new Match(entry.id(), normAlias, score);
                        fuzzyAliasLen = normAlias.length();
                    } else if (score == bestFuzzy.score()) {
                        int len = normAlias.length();
                        if (len > fuzzyAliasLen
                                || (len == fuzzyAliasLen && entry.id().compareTo(bestFuzzy.entryId()) < 0)) {
                            bestFuzzy = new Match(entry.id(), normAlias, score);
                            fuzzyAliasLen = len;
                        }
                    }
                }
            }
        }
        return new Result(bestVerbatim, bestFuzzy);
    }

    /** ASR errors are phonetic: 换蛋/幻弹 share pinyin, falsome/falsum share
     *  the consonant skeleton — character Levenshtein alone scores both at
     *  0.0-0.71. Take the best of orthographic and phonetic similarity. */
    private static float fuzzy(String alias, String text) {
        return Math.max(similarity(alias, text), Phonetics.score(alias, text));
    }

    /** Levenshtein-based similarity in [0,1], comparing against the longest string. */
    static float similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1f;
        return 1.0f - (float) Levenshtein.distance(a, b) / maxLen;
    }

    static String normalize(String s) {
        if (s == null) return "";
        // \p{L}\p{N} keep letters/digits of ALL scripts, so CJK aliases
        // (e.g. "爆裂") survive normalization.
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}'\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsWord(String haystack, String word) {
        int idx = haystack.indexOf(word);
        while (idx >= 0) {
            boolean beforeOk = idx == 0 || isBoundary(haystack.charAt(idx - 1));
            int end = idx + word.length();
            boolean afterOk = end == haystack.length() || isBoundary(haystack.charAt(end));
            if (beforeOk && afterOk) return true;
            idx = haystack.indexOf(word, idx + 1);
        }
        return false;
    }

    /**
     * A word boundary is whitespace or any CJK-range character: after
     * normalization the text is a mix of latin words and CJK runs, and a CJK
     * alias must count as a whole "word" when directly adjacent to other CJK
     * characters is NOT required (e.g. 火球爆裂啊 contains 爆裂).
     */
    private static boolean isBoundary(char c) {
        return c <= ' ' || c >= 0x2E80;
    }
}
