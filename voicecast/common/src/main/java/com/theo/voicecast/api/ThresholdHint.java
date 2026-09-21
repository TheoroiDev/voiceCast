package com.theo.voicecast.api;

/**
 * Per-vocabulary-entry threshold hint (semantic contract v2, C1b §0.3):
 * game-side tuning data pushed WITH the vocabulary. A {@code null} component
 * means "use the engine calibration default" ({@link Calibration}); a value
 * replaces it for this entry only. Data crosses the mod boundary — the
 * threshold application logic does not.
 *
 * <p>Content owners (WizardReal) derive hints from per-spell {@code
 * threshold} overrides, per-mode calibration rows and the reject-level
 * overlay. A component above 1.0 acts as "tier disabled for this entry"
 * (no score can reach it) — that is how the reject levels suppress the
 * snap-to-nearest surfaces.
 *
 * @param forward CTC posterior acceptance threshold (forward scoring tier)
 * @param phoneme phoneme-similarity threshold (weighted-edit-distance tier)
 * @param text    text-similarity threshold (alias matching tier)
 */
public record ThresholdHint(Float forward, Float phoneme, Float text) {
    /** Hint that overrides every tier with one value (per-spell semantics). */
    public static ThresholdHint all(float value) {
        return new ThresholdHint(value, value, value);
    }

    /** Disabled-tier marker used by reject-level overlays. */
    public static final float DISABLED = 1.01f;
}
