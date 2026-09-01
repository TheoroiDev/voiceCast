package com.theo.voicecast.api;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared IPA text utilities: normalization (stress/diacritic stripping plus the
 * systematic wav2vec2-espeak confusions documented in
 * {@code workspace-root docs/IPA识别问题.md}) and phoneme tokenization.
 *
 * <p>Used by the IPA engine to turn {@link Pronunciation#ipa()} templates into
 * comparable token sequences for CTC vocabulary scoring, and by WizardReal's
 * phoneme matcher so both sides always normalize identically.
 */
public final class IpaText {
    private IpaText() {}

    /** Normalize tokens already split by the engine (one phoneme per element). */
    public static List<String> normalizeTokens(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            String n = stripDiacritics(t);
            if (n.isEmpty()) continue;
            // engine may join phonemes with spaces; split defensively
            for (String p : tokenize(n)) out.add(p);
        }
        return out;
    }

    /** Split an IPA string into phoneme tokens (multi-char affricates kept together). */
    public static List<String> tokenize(String ipa) {
        String s = stripDiacritics(ipa);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            // keep common two-char affricates/diphthongs together
            if (i + 1 < s.length()) {
                String two = s.substring(i, i + 2);
                if (two.equals("tʃ") || two.equals("dʒ") || two.equals("ts") || two.equals("dz")) {
                    out.add(two);
                    i += 2;
                    continue;
                }
            }
            out.add(String.valueOf(c));
            i++;
        }
        return out;
    }

    /** Remove stress/length/tone marks and non-essential diacritics; keep base letters. */
    public static String stripDiacritics(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue; // combining diacritics
            if (c == 'ˈ' || c == 'ˌ' || c == 'ː' || c == '.' || c == '-') continue;
            sb.append(c);
        }
        String out = sb.toString().toLowerCase(Locale.ROOT).trim();
        // Lateral normalization (workspace-root docs/IPA识别问题.md): wav2vec2-espeak emits the
        // dark/velarized "ɫ" (U+026B) for syllable-final "l" (e.g.
        // fulmen ˈfʊɫmɛn), while our templates use clear "l" (U+006C). Map the
        // lateral family onto "l" so both realizations match.
        out = out.replace('\u026B', 'l')  // ɫ velarized lateral
                 .replace('\u026C', 'l')  // ɬ voiceless lateral fricative
                 .replace('\u026E', 'l'); // ɮ voiced lateral fricative
        // Vowel equivalence classes (workspace-root docs/IPA识别问题.md, user testing): the
        // engine systematically shifts tense/lax vowels for non-native speech
        // ("fulmen" ˈfʊlmɛn was heard as [f uː m ʌ n]). Merge each near-pair
        // onto one symbol so the matcher tolerates the offset:
        //   ɪ→i, ʊ→u (lax high vowels -> tense), ɛ→e, ʌ→ə (open-mid -> mid).
        out = out.replace('ɪ', 'i')
                 .replace('ʊ', 'u')
                 .replace('ɛ', 'e')
                 .replace('ʌ', 'ə');
        return out;
    }
}
