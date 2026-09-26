package com.theo.voicecast.match;

import java.util.List;

/**
 * Per-line chant progression matcher (voiceCast#48). The chant state machine
 * (wizardreal ChantEngine) asks this interface — and ONLY this interface —
 * whether the just-heard utterance matches the line being chanted. Bindings:
 * {@link LenientLineMatcher} (the pre-#48 lenient boolean, also the fallback
 * inside the adjudicator-backed binding) and
 * {@link AdjudicatorBackedChantLineMatcher} (the production adjudicator's
 * verdict for the line, W2 — progression authority lives in voicecast, C1b).
 *
 * <p>The signature is deliberately built from plain types (line identity +
 * template/alias lists + the heard utterance + the last adjudication) so
 * voicecast implementations never reference wizardreal types — the dependency
 * arrow stays wizardreal → voicecast.
 */
public interface ChantLineMatcher {

    /**
     * Decide whether the utterance matches the chant line.
     *
     * @param linePronId the line's pronunciation id (adjudicator-verdict key)
     * @param lineIpa    the line's curated IPA templates (may be empty)
     * @param aliases    the line's text aliases (may be empty)
     * @param heard      the text-lane utterance ("" = absent)
     * @param heardIpa   the phoneme-lane utterance tokens (empty = absent)
     * @param verdict    the utterance's production adjudication (may be null —
     *                   e.g. offline replay paths without an adjudicator run);
     *                   pronId/decision/score as produced by the recognizer
     * @return the match outcome (W2: boolean; W3 adds score/alignment)
     */
    LineMatch match(String linePronId, List<String> lineIpa, List<String> aliases,
                    String heard, List<String> heardIpa, ChantVerdict verdict);

    /** The utterance-level adjudication, projected for line matching. */
    record ChantVerdict(String pronId, String decision, float score) {
        public boolean exact()  { return "EXACT".equals(decision); }
        public boolean near()   { return "NEAR".equals(decision); }
    }

    /** One line-match outcome. {@code score} is 1.0/0.0 for boolean matchers;
     *  strategy matchers (W3+) fill a graded value in [0,1]. */
    record LineMatch(boolean matched, float score) {
        public static LineMatch of(boolean matched) {
            return new LineMatch(matched, matched ? 1.0f : 0.0f);
        }
    }
}
