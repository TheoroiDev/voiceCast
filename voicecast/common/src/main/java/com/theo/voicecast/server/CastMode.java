package com.theo.voicecast.server;

/**
 * Casting-time vocabulary routing modes (0.5.0, issue #30; the D-15 four-mode
 * decision, D-20260915-15). The session's cast mode decides which subset of
 * the pushed vocabulary reaches the recognizer grammar — fewer competing
 * entries means fewer cross-spell false triggers and sharper decoding, at the
 * cost of what the recognizer can hear at all.
 *
 * <ul>
 *   <li>{@link #OPEN} — free-casting default: the full vocabulary (every
 *       spell's every alias), the same candidate set as the pre-#30 default —
 *       the only narrowing is the engine-language projection every mode
 *       composes with. (A trigger+release refinement shipped in 0.5.0 was
 *       reverted: under the production SpellMatcher's Phonetics layer it
 *       re-shuffles rather than removes false triggers — supervisor ruling on
 *       the P30 re-verification.) The mode id remains so servers can declare
 *       free casting explicitly and future semantics can diverge again.</li>
 *   <li>{@link #CHANT_CONFIRM} — ladder chant in progress: the declared
 *       spell's every alias (trigger + all chant lines), so each line is
 *       judged against its own templates only.</li>
 *   <li>{@link #PRACTICE_CONFIRM} — practice surfaces (B crystal / E guide /
 *       mentor NPC, M4): the declared spell's every alias. Same candidate
 *       shape as {@link #CHANT_CONFIRM}; separate id so calibration data and
 *       future practice-only behavior can diverge.</li>
 *   <li>{@link #GRAY_NARROW} — gray rollout narrowing (phase 2): the declared
 *       spell plus its top-K confusion neighbors ({@code
 *       assets/voicecast/confusion_neighbors.tsv}, K=3).</li>
 * </ul>
 *
 * <p>All modes compose with the engine-language projection (the routed
 * candidates are intersected with the engine's language buckets, "∩单语言桶"
 * in the D-15 table). Engines without a language (ipa-phonemes, noop) are
 * never mode-routed: the IPA line stays full-vocabulary (issue #30 D5).
 * A session with no declared mode (the library default) is routed exactly as
 * before this enum existed.
 */
public enum CastMode {
    OPEN,
    CHANT_CONFIRM,
    PRACTICE_CONFIRM,
    GRAY_NARROW
}
