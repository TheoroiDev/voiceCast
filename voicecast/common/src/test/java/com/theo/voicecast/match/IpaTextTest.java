package com.theo.voicecast.match;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** IPA normalization/tokenization (moved from WizardReal's PhonemeMatcherTest). */
class IpaTextTest {

    @Test
    void tokenizeStripsStressLengthAndDots() {
        assertEquals(List.of("f", "u", "l", "m", "e", "n"),
                IpaText.tokenize("ˈfʊːl.mɛn"));
    }

    @Test
    void tokenizeKeepsAffricatesTogether() {
        assertEquals(List.of("tʃ"), IpaText.tokenize("tʃ"));
        assertEquals(List.of("dʒ", "a"), IpaText.tokenize("dʒa"));
        assertEquals(List.of("ts", "u"), IpaText.tokenize("tsu"));
        assertEquals(List.of("t", "s"), IpaText.tokenize("t s"));
    }

    @Test
    void normalizeTokensAppliesVowelClasses() {
        // lax->tense and open-mid->mid mappings from workspace-root docs/IPA识别问题.md
        assertEquals(List.of("f", "u", "m", "ə", "n"),
                IpaText.normalizeTokens(List.of("f", "ʊ", "m", "ʌ", "n")));
        // lateral normalization: ɫ -> l
        assertEquals(List.of("l"), IpaText.normalizeTokens(List.of("ɫ")));
        // engine may join phonemes with spaces; split defensively
        assertEquals(List.of("f", "u", "m"), IpaText.normalizeTokens(List.of("f u m")));
    }
}
