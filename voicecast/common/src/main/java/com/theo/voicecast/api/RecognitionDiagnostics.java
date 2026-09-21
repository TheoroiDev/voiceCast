package com.theo.voicecast.api;

import java.util.Map;

/**
 * Standalone diagnostics accessor for one adjudication (semantic contract
 * v2, C1b §0.1): the engine-internal numbers that used to leak through the
 * result contract (CTC template posteriors, the ctcPresent flag, margin
 * gaps) plus the rejection reason. NOT part of {@link RecognitionResult} —
 * reachable via {@code SpeechRecognizer#lastDiagnostics()} and verbose logs,
 * never a decision input for consumers.
 */
public record RecognitionDiagnostics(
        /** Which evidence tier produced the decision (or would have). */
        String source,
        /** Why AMBIGUOUS/REJECTED happened (null when EXACT/NEAR won). */
        String rejectionReason,
        /** CTC margin evidence: pre-margin top1 posterior (0 when absent). */
        float marginTop1,
        /** Best NON-top1 template posterior (0 when no runner-up). */
        float marginTop2,
        /** Whether the CTC margin gate rejected the top1 winner. */
        boolean marginRejected,
        /** Post-margin CTC posteriors keyed by pronunciation id (empty when
         *  the engine has no CTC line). The old templateScores map, now
         *  diagnostics-only. */
        Map<String, Float> templateScores
) {
    /** {@code true} when a CTC scoring pass ran for this utterance — the
     *  diagnostics-only heir of the removed {@code ctcPresent} flag. */
    public boolean ctcPresent() {
        return templateScores != null && !templateScores.isEmpty();
    }
}
