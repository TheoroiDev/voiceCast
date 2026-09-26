package com.theo.voicecast.match;

import java.util.List;

/**
 * Per-line chant progression matcher (voiceCast#48 W1 seam). The chant state
 * machine (wizardreal ChantEngine) asks this interface — and ONLY this
 * interface — whether the just-heard utterance matches the line being chanted.
 * The default binding is {@link LenientLineMatcher} (byte-identical to the
 * pre-#48 inline boolean); later bindings (voiceCast#48 W2+) may route the
 * decision through the production adjudicator, fuse both engine lanes, or
 * apply per-mode strictness.
 *
 * <p>The signature is deliberately built from plain types (template/alias
 * lists + the heard utterance) so voicecast implementations never reference
 * wizardreal types — the dependency arrow stays wizardreal → voicecast.
 */
public interface ChantLineMatcher {

    /**
     * Decide whether {@code heard} / {@code heardIpa} matches the chant line
     * described by its pronunciation surface.
     *
     * @param lineIpa  the line's curated IPA templates (may be empty)
     * @param aliases  the line's text aliases (may be empty)
     * @param heard    the text-lane utterance ("" = absent)
     * @param heardIpa the phoneme-lane utterance tokens (empty = absent)
     * @return the match outcome (W1: boolean only; W3 adds score/alignment)
     */
    LineMatch match(List<String> lineIpa, List<String> aliases,
                    String heard, List<String> heardIpa);

    /** One line-match outcome. {@code score} is 1.0/0.0 for boolean matchers;
     *  strategy matchers (W3+) fill a graded value in [0,1]. */
    record LineMatch(boolean matched, float score) {
        public static LineMatch of(boolean matched) {
            return new LineMatch(matched, matched ? 1.0f : 0.0f);
        }
    }
}
