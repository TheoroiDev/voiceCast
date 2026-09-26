package com.theo.voicecast.match;

import java.util.List;

/**
 * The pre-#48 behavior, verbatim: lenient IPA-phonetic first, then lenient
 * text — each delegating to {@link LenientLine}. Kept byte-compatible so the
 * W1 seam (matcher injection into the chant state machine) is provably
 * behavior-free (chant sequence bench must diff clean against the W1
 * baseline).
 */
public final class LenientLineMatcher implements ChantLineMatcher {

    public static final LenientLineMatcher INSTANCE = new LenientLineMatcher();

    private LenientLineMatcher() {}

    @Override
    public LineMatch match(String linePronId, List<String> lineIpa, List<String> aliases,
                           String heard, List<String> heardIpa, ChantVerdict verdict) {
        if (heardIpa != null && !heardIpa.isEmpty() && lineIpa != null && !lineIpa.isEmpty()) {
            String ipaText = String.join(" ", heardIpa);
            for (String templ : lineIpa) {
                if (LenientLine.loosePhonetic(ipaText, templ)) return LineMatch.of(true);
            }
        }
        if (heard != null && !heard.isBlank()) {
            for (String alias : aliases) {
                if (LenientLine.looseText(heard.toLowerCase(java.util.Locale.ROOT),
                        alias.toLowerCase(java.util.Locale.ROOT))) return LineMatch.of(true);
            }
        }
        return LineMatch.of(false);
    }
}
