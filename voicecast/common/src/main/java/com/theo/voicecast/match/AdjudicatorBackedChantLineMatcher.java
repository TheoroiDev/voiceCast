package com.theo.voicecast.match;

import java.util.List;

/**
 * voiceCast#48 W2 binding: the production adjudicator's verdict for the line
 * decides progression — the C1b exception ("chant lines judged outside
 * voicecast") closes here. A line matches when the utterance's adjudication
 * names the line's pronunciation id with a passing tier (EXACT always; NEAR
 * at/above the line threshold). Anything else falls back to the lenient
 * boolean — verdicts arrive only for lines present in the routed session
 * vocabulary (g2p drafts included), so out-of-vocabulary lines and offline
 * replay paths keep working instead of regressing to never-match.
 *
 * <p>NEAR keeps matching (rather than requiring EXACT) so the W2 default is
 * an upper bound of the lenient behavior: strictly more evidence, never less
 * progression. Tightening belongs to the W3 per-mode strategy.
 */
public final class AdjudicatorBackedChantLineMatcher implements ChantLineMatcher {

    /** Line NEAR floor for the verdict path (verdict scores are the full
     *  adjudicator's graded similarity; the lenient fallback stays available
     *  below it). 0 = accept any NEAR naming the line. */
    public static final float NEAR_LINE_FLOOR = 0.0f;

    private final ChantLineMatcher fallback;

    public AdjudicatorBackedChantLineMatcher() {
        this(LenientLineMatcher.INSTANCE);
    }

    public AdjudicatorBackedChantLineMatcher(ChantLineMatcher fallback) {
        this.fallback = fallback == null ? LenientLineMatcher.INSTANCE : fallback;
    }

    @Override
    public LineMatch match(String linePronId, List<String> lineIpa, List<String> aliases,
                           String heard, List<String> heardIpa, ChantVerdict verdict) {
        if (verdict != null && linePronId != null && linePronId.equals(verdict.pronId())
                && (verdict.exact() || (verdict.near() && verdict.score() >= NEAR_LINE_FLOOR))) {
            return new LineMatch(true, verdict.exact() ? 1.0f : verdict.score());
        }
        return fallback.match(linePronId, lineIpa, aliases, heard, heardIpa, verdict);
    }
}
