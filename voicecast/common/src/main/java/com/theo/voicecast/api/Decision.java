package com.theo.voicecast.api;

/**
 * The adjudicated verdict for one final utterance (semantic contract v2,
 * engine-swap C1b). Voicecast owns the decision: it fuses the engine's
 * evidence lines (text line, phoneme line, CTC template posteriors) against
 * the routed session vocabulary and answers "what was said" —
 * {@link #EXACT}/{@link #NEAR} name the winning pronunciation, the others
 * name why nothing was accepted.
 *
 * <p>Priorities (work order C1b §0.2): qwen3/text EXACT &gt; zipa EXACT &gt;
 * text NEAR &gt; zipa NEAR &gt; AMBIGUOUS/REJECTED. Consumers (WizardReal)
 * only read {@link RecognitionResult#decision()}/{@code spellId}/
 * {@code score} plus the alternatives — engine internals (CTC posteriors,
 * margins) stay behind {@link RecognitionDiagnostics}.
 */
public enum Decision {
    /** Authoritative hit of its line: verbatim alias text, or a CTC posterior
     *  at/above the effective forward threshold. */
    EXACT,
    /** Fuzzy hit: phoneme/text similarity at/above the effective threshold. */
    NEAR,
    /** The CTC line's best template passed the forward threshold but lost to
     *  a runner-up inside the CTC margin — the win is ambiguous. Still falls
     *  through to every NEAR tier (rejection reason in diagnostics). */
    AMBIGUOUS,
    /** No vocabulary entry cleared any tier (reason in diagnostics). */
    REJECTED
}
