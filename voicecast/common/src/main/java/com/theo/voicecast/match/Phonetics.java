package com.theo.voicecast.match;

import com.ibm.icu.text.Transliterator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phonetic similarity layer for {@link SpellMatcher}.
 *
 * <p>ASR errors are mostly phonetic, not orthographic: 换蛋/幻弹 are
 * identical in pinyin, "falsome"/"falsum" share the consonant skeleton, while
 * character-level Levenshtein scores both at 0.0-0.71 (below the 0.8
 * threshold). This class scores pairs on their phonetic representation:
 *
 * <ol>
 *   <li>ICU "Any-Latin; Latin-ASCII" transliteration — hanzi becomes pinyin,
 *       kana becomes romaji, latin passes through (speech-to-phoneme
 *       normalization independent of the ASR's script choice)</li>
 *   <li>consonant-skeleton Jaccard — vowels are dropped (first letter kept),
 *       so vowel mistakes and single insertions barely move the score</li>
 * </ol>
 *
 * <p>Both layers are best-effort: if ICU is unavailable the transliteration
 * is the identity function and the skeleton still applies to latin text.
 */
public final class Phonetics {
    private static final Object INIT_LOCK = new Object();
    private static volatile Transliterator latinizer;
    private static volatile boolean latinizerFailed;

    private Phonetics() {}

    /** Hanzi→pinyin / kana→romaji / passthrough-latin, lower-cased ASCII. */
    static String latinize(String s) {
        if (s == null || s.isEmpty()) return "";
        Transliterator t = latinizer();
        if (t == null) return s.toLowerCase(Locale.ROOT);
        synchronized (INIT_LOCK) {
            return t.transform(s).toLowerCase(Locale.ROOT);
        }
    }

    private static Transliterator latinizer() {
        if (latinizer == null && !latinizerFailed) {
            synchronized (INIT_LOCK) {
                if (latinizer == null && !latinizerFailed) {
                    try {
                        latinizer = Transliterator.getInstance("Any-Latin; Latin-ASCII");
                    } catch (Throwable t) {
                        latinizerFailed = true;
                    }
                }
            }
        }
        return latinizer;
    }

    /** Phonetic similarity of two normalized strings in [0,1]. */
    public static float score(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        String la = latinize(a);
        String lb = latinize(b);
        float full = similarity(la, lb);

        List<String> ta = tokenize(la);
        List<String> tb = tokenize(lb);
        float token = tokenScore(ta, tb);
        return Math.max(full, token);
    }

    /** Best-pair average: every alias token matches its best text token or
     *  the concatenation of two adjacent text tokens (ASR splits words:
     *  "fulgur" -> "full ger"). */
    private static float tokenScore(List<String> ta, List<String> tb) {
        if (ta.isEmpty() || tb.isEmpty()) return 0f;
        List<String> concat = new ArrayList<>(tb);
        for (int i = 0; i + 1 < tb.size(); i++) concat.add(tb.get(i) + tb.get(i + 1));
        float total = 0f;
        for (String a : ta) {
            float best = 0f;
            for (String b : concat) {
                best = Math.max(best, tokenSim(a, b));
            }
            total += best;
        }
        return total / ta.size();
    }

    private static float tokenSim(String a, String b) {
        float lev = similarity(a, b);
        if (lev >= 1f) return 1f;
        // vowel-stripped lev: ASR vowel substitutions are the most common
        // latin error (explion/explosion). Only for tokens >= 4 chars — the
        // keep-first-char rule makes short prefixes score too high (ign vs
        // ignis = 0.75, a fragment that should NOT match).
        float vowelless = 0f;
        if (Math.min(a.length(), b.length()) >= 4) {
            vowelless = similarity(stripVowels(a), stripVowels(b));
        }
        // metaphone: consonant classes survive ASR substitutions and TTS
        // pronunciation drift (chaos -> "kas" == metaphone KAS)
        float meta = similarity(metaphone(a), metaphone(b));
        return Math.max(lev, Math.max(vowelless, Math.max(meta, jaccard(skeleton(a), skeleton(b)))));
    }

    /** Classic Metaphone key (Lawrence Philips 1990), compact variant for
     *  short name tokens: consonant classes survive ASR substitutions and TTS
     *  pronunciation drift (chaos -> "kas" both reduce to KAS). */
    static String metaphone(String word) {
        String w = word.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        int n = w.length();
        if (n == 0) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        if (w.startsWith("CH")) {
            sb.append('K'); i = 2; // chaos == kas
        } else if (w.startsWith("PH")) {
            sb.append('F'); i = 2; // phantom == fant-ish
        } else if (w.startsWith("KN") || w.startsWith("GN") || w.startsWith("PN")
                || w.startsWith("WR") || w.startsWith("AE")) {
            i = 1; // silent initial
        } else if (w.startsWith("WH")) {
            sb.append('W'); i = 2;
        } else if (w.startsWith("X")) {
            sb.append('S'); i = 1;
        } else {
            sb.append(w.charAt(0)); i = 1;
        }
        while (i < n) {
            char cur = w.charAt(i);
            char prev = i > 0 ? w.charAt(i - 1) : ' ';
            char next = i + 1 < n ? w.charAt(i + 1) : ' ';
            char after = i + 2 < n ? w.charAt(i + 2) : ' ';
            if (cur == prev && cur != 'C') { i++; continue; }
            switch (cur) {
                case 'A', 'E', 'I', 'O', 'U' -> { }
                case 'B' -> { if (!(prev == 'M' && i == n - 1)) sb.append('B'); }
                case 'C' -> {
                    if (next == 'H') { sb.append('X'); i++; }
                    else if (prev == 'S' && "EIY".indexOf(next) >= 0) { }
                    else sb.append('K');
                }
                case 'D' -> {
                    if (next == 'G' && "EIY".indexOf(after) >= 0) { sb.append('J'); i++; }
                    else sb.append('T');
                }
                case 'G' -> {
                    if (next == 'H') i++;
                    else if (next == 'N') { }
                    else if (prev == 'G') { }
                    else if ("EIY".indexOf(next) >= 0) sb.append('J');
                    else sb.append('K');
                }
                case 'H' -> { if (i == 0 || "CSPTG".indexOf(prev) < 0) sb.append('H'); }
                case 'K' -> { if (prev != 'C') sb.append('K'); }
                case 'P' -> { if (next == 'H') { sb.append('F'); i++; } else sb.append('P'); }
                case 'Q' -> sb.append('K');
                case 'S' -> { if (next == 'H') { sb.append('X'); i++; } else sb.append('S'); }
                case 'T' -> {
                    if (next == 'I' && (after == 'O' || after == 'A')) sb.append('X');
                    else if (next == 'H') { sb.append('T'); i++; }
                    else sb.append('T');
                }
                case 'V' -> sb.append('F');
                case 'W', 'Y' -> { if (isVowel(next)) sb.append(cur); }
                case 'X' -> { sb.append('K'); sb.append('S'); }
                case 'Z' -> sb.append('S');
                case 'F', 'J', 'L', 'M', 'N', 'R' -> sb.append(cur);
                default -> { }
            }
            i++;
        }
        return sb.toString();
    }


    private static boolean isVowel(char c) {
        return "AEIOU".indexOf(c) >= 0;
    }

    /** Tokens of latinized text (letters/digits/apostrophes kept). */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("[^\\p{L}\\p{N}']+")) {
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /** Consonant skeleton: first char + non-vowels (vowel mistakes vanish). */
    private static Set<String> skeleton(String token) {
        // bigram skeleton is order-tolerant enough for ASR swaps while still
        // discriminating between distinct short words
        String skel = stripVowels(token);
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < skel.length(); i++) grams.add(skel.substring(i, i + 2));
        if (grams.isEmpty() && !skel.isEmpty()) grams.add(skel);
        return grams;
    }

    private static String stripVowels(String token) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (i == 0 || "aeiou".indexOf(c) < 0) sb.append(c);
        }
        return sb.toString();
    }

    private static float jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0f;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (float) inter.size() / union.size();
    }

    private static float similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1f;
        return 1.0f - (float) levenshtein(a, b) / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
