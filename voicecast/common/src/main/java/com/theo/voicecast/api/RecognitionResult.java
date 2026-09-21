package com.theo.voicecast.api;

import java.util.List;

/**
 * One recognition utterance (semantic contract v2, engine-swap C1b). The
 * decision is voicecast's: {@link #decision()} names what was said against
 * the routed session vocabulary — {@code spellId}/{@code pronId} identify
 * the winning entry, {@code score} its evidence, {@code alternatives} the
 * runner-ups (top-k &le; 3). {@code utteranceText} is the text line's output
 * (may be a phoneme token join on IPA engines); {@code ipa} is the heard
 * IPA string (space-joined phoneme tokens, empty on text-only engines);
 * {@code language} is the engine's language bucket ("" when language-
 * agnostic).
 *
 * <p>Partial results (HUD readout only) carry a null decision — only final
 * results are adjudicated. Consumers must not read decision fields of
 * partials.
 */
public record RecognitionResult(
        String utteranceText,
        String ipa,
        String language,
        Decision decision,
        String spellId,
        String pronId,
        float score,
        List<Alternative> alternatives,
        long startMs,
        long endMs
) {
    public RecognitionResult {
        utteranceText = utteranceText == null ? "" : utteranceText;
        ipa = ipa == null ? "" : ipa;
        language = language == null ? "" : language;
        spellId = spellId == null ? "" : spellId;
        pronId = pronId == null ? "" : pronId;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    /** HUD-only partial: no decision yet. */
    public static RecognitionResult partial(String utteranceText, String ipa) {
        long now = System.currentTimeMillis();
        return new RecognitionResult(utteranceText, ipa, "", null, "", "", 0f, List.of(), now, now);
    }

    /** Adjudicated final result (built by the voicecast adjudicator). */
    public static RecognitionResult finality(String utteranceText, String ipa, String language,
                                             Decision decision, String spellId, String pronId,
                                             float score, List<Alternative> alternatives, long startMs) {
        return new RecognitionResult(utteranceText, ipa, language, decision, spellId, pronId,
                score, alternatives, startMs, System.currentTimeMillis());
    }

    /** Rejection without a candidate (nothing remotely matched). */
    public static RecognitionResult rejected(String utteranceText, String ipa, String language,
                                             long startMs) {
        return finality(utteranceText, ipa, language, Decision.REJECTED, "", "", 0f, List.of(), startMs);
    }
}
