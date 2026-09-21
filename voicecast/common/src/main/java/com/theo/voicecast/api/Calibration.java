package com.theo.voicecast.api;

/**
 * Engine-calibration defaults for the semantic adjudication (C1b §0.3): the
 * margin and the phoneme/text/forward thresholds OWNED by voicecast config
 * ({@code [match]} in the server toml). Per-entry hints
 * ({@link ThresholdHint}) override these per vocabulary entry; the values
 * shipped here are the calibrated defaults (R3 移植 / S6-FINAL / m1_retest).
 *
 * <p>Travels to recognizers inside {@link SpeechOptions}.
 */
public record Calibration(float forward, float phoneme, float text, float margin) {
    /** The calibrated shipped defaults (= the removed WizardReal
     *  FORWARD_MATCH_THRESHOLD 0.10 + the matcher constants 0.6/0.65 + the
     *  CTC margin 0.02). */
    public static final Calibration DEFAULT = new Calibration(0.10f, 0.6f, 0.65f, 0.02f);
}
