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
     *  0.0-0.71. Take the best of orthographic and phonetic similarity.
     *
     *  <p>Pre-gates (voiceCast#47 E, the "词数相差过大" rule): the best-pair
     *  token average lets every alias token find SOME loose buddy anywhere in
     *  the utterance, and char similarity lets a short alias ride on one
     *  near-identical word — so a recited sentence scored short trigger
     *  aliases ~0.75 and fired them idle (real case: "the pyre remembers my
     *  name" → torpor NEAR; "fire" ↔ "pyre" alone = 0.75). Two classical
     *  pruning filters from set-similarity joins run before scoring:
     *  <ul>
     *  <li><b>length filter</b> (PPJoin-family, pigeonhole on the score
     *  bound): the alias must span at least half the utterance's normalized
     *  characters — kills short-alias-in-long-sentence at any token size;</li>
     *  <li><b>overlap coefficient</b> (multi-token alias x multi-token
     *  utterance only): at least half the alias's own words must appear
     *  verbatim in the utterance — kills similar-length-different-words
     *  collisions ("...remembers my name" vs "hear the thunder" share only
     *  "the"). ASR-confused SINGLE words skip both gates, so half-spoken
     *  chants ("ign"→"ignis") and mishearings ("falsome"→"falsum") are
     *  unaffected.</li>
     *  </ul> */
    private static float fuzzy(String alias, String text) {
        if (!fuzzyGatesHit(alias, text)) return 0f;
        if (!sameScriptFamily(alias, text)) return 0f; // #49② language-consistency gate
        float literal = similarity(alias, text);
        // Phonetics-only hits are trusted only while the utterance does NOT
        // outgrow the alias (see FUZZY_MIN_LITERAL_FOR_PHONETICS) — an
        // equal-length pure-homophone transcription is a correctly SPOKEN
        // alias the ASR just spelled with different hanzi.
        boolean outgrown = normalize(text).length() > normalize(alias).length();
        if (outgrown && literal < FUZZY_MIN_LITERAL_FOR_PHONETICS) return literal;
        return Math.max(literal, Phonetics.score(alias, text));
    }

    /** Coarse script family: CJK (hanzi + kana — ja aliases share the zh
     *  writing system), LATIN, or MIXED. #49② language-consistency gate: the
     *  Phonetics layer transliterates EVERYTHING to latin, so an equal-length
     *  cross-script pair ("今天阳光真好" vs en "catena", literal 0.0) still
     *  scores ~0.67 through the best-pair average — unrelated scripts must
     *  never fuzzy-match. MIXED passes (mixed-script aliases are legal). */
    static boolean sameScriptFamily(String alias, String text) {
        return scriptFamily(normalize(alias)) == scriptFamily(normalize(text))
                || scriptFamily(normalize(alias)) == ScriptFamily.MIXED
                || scriptFamily(normalize(text)) == ScriptFamily.MIXED;
    }

    enum ScriptFamily { CJK, LATIN, MIXED }

    static ScriptFamily scriptFamily(String s) {
        boolean cjk = false, latin = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
            boolean isCjk = (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || b == Character.UnicodeBlock.HIRAGANA
                    || b == Character.UnicodeBlock.KATAKANA);
            if (isCjk) cjk = true;
            else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) latin = true;
        }
        if (cjk && latin) return ScriptFamily.MIXED;
        if (cjk) return ScriptFamily.CJK;
        if (latin) return ScriptFamily.LATIN;
        return ScriptFamily.MIXED; // digits/punct-only: don't gate
    }

    /** Floor for the alias's share of the utterance's characters (length filter). */
    private static final double FUZZY_MIN_CHAR_RATIO = 0.5;
    /** Symmetric ceiling (wr#35, #48 W3): the utterance may outgrow the alias
     *  by at most this factor. zh chitchat recheck 2026-09-26: 117 negatives
     *  scored zh FPR 100% (en 0/57 — the length/overlap gates already cover
     *  latin) because the 0.5 floor alone admits a 6-char sentence against a
     *  3-char alias at exactly 0.5. Same-length homophone hits are untouched
     *  ("施暴" for 尸爆, "练电" for 链电). */
    private static final double FUZZY_MAX_UTTERANCE_RATIO = 1.8;
    /** Floor on the LITERAL (Levenshtein) similarity for a Phonetics-only hit
     *  that OUTGROWS the alias: a longer utterance is a sentence that merely
     *  contains a near-homophone run ("今天阳光真好" → 圣光箭 via the shared
     *  "guang"), not a spoken alias. Equal-length pure homophones are the
     *  zh incantation norm and stay exempt (the floor made zh triggers
     *  collapse to 0/234 — every miss was an equal-length homophone pair). */
    private static final float FUZZY_MIN_LITERAL_FOR_PHONETICS = 0.4f;
    /** Floor for the share of the alias's own words found in the utterance. */
    private static final double FUZZY_MIN_TOKEN_OVERLAP = 0.5;

    static boolean fuzzyGatesHit(String alias, String text) {
        String na = normalize(alias);
        String nt = normalize(text);
        if (na.isEmpty() || nt.isEmpty()) return false;
        if (na.length() < nt.length() * FUZZY_MIN_CHAR_RATIO) return false;
        if (nt.length() > na.length() * FUZZY_MAX_UTTERANCE_RATIO) return false;
        List<String> a = java.util.Arrays.stream(na.split(" "))
                .filter(s -> !s.isEmpty()).toList();
        List<String> t = java.util.Arrays.stream(nt.split(" "))
                .filter(s -> !s.isEmpty()).toList();
        // Multi-word ALIAS gate fires regardless of utterance token count: a
        // recited single word must not ride a whole phrase alias on its first
        // word's vowelless skeleton (real case: sensevoice "auro" scored
        // "aurae levitas" 1.0 — aurae/auro strip to the same "r").
        if (a.size() >= 2) {
            long hits = a.stream().filter(t::contains).distinct().count();
            if (hits / (double) a.size() < FUZZY_MIN_TOKEN_OVERLAP) return false;
        }
        return true;
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
