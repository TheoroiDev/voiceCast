package com.theo.voicecast.api;

import java.util.List;
import java.util.Map;

/**
 * One recognition utterance. {@code ipaTokens} is the primary output for
 * phoneme-based matchers; {@code text} is present for text/word recognizers
 * and may be empty for pure-IPA engines.
 *
 * <p>{@code templateScores} carries optional CTC vocabulary scores computed by
 * the IPA engine at decode time: pronunciation id -&gt; posterior probability
 * in [0,1] of that pronunciation being what was said (softmax over the pushed
 * vocabulary's IPA templates plus a "nothing said" null competitor). Empty for
 * text engines or when the engine has no IPA vocabulary.
 */
public record RecognitionResult(
        String text,
        List<String> ipaTokens,
        float confidence,
        long startMs,
        long endMs,
        boolean partial,
        Map<String, Float> templateScores
) {
    public RecognitionResult {
        text = text == null ? "" : text;
        ipaTokens = ipaTokens == null ? List.of() : List.copyOf(ipaTokens);
        templateScores = templateScores == null ? Map.of() : Map.copyOf(templateScores);
    }

    public static RecognitionResult partial(String text, List<String> ipa, float confidence) {
        long now = System.currentTimeMillis();
        return new RecognitionResult(text, ipa, confidence, now, now, true, Map.of());
    }

    public static RecognitionResult finality(String text, List<String> ipa, float confidence, long startMs) {
        return finality(text, ipa, confidence, startMs, null);
    }

    public static RecognitionResult finality(String text, List<String> ipa, float confidence, long startMs,
                                             Map<String, Float> templateScores) {
        return new RecognitionResult(text, ipa, confidence, startMs, System.currentTimeMillis(), false, templateScores);
    }
}
