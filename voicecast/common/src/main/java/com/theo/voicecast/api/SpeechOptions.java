package com.theo.voicecast.api;

/**
 * Tunables for a {@link SpeechRecognizer} session.
 *
 * @param pushToTalk if true, recognition only runs while the PTT key is held
 * @param minConfidence recognizer-level confidence floor (0..1)
 * @param modelPath relative or absolute path to the active model directory
 * @param grammarOnly if true, recognition is restricted to registered vocabulary
 * @param calibration engine-calibration defaults for the semantic adjudication
 *        (null = the shipped {@link Calibration#DEFAULT})
 */
public record SpeechOptions(
        boolean pushToTalk,
        float minConfidence,
        String modelPath,
        boolean grammarOnly,
        Calibration calibration
) {
    public SpeechOptions {
        calibration = calibration == null ? Calibration.DEFAULT : calibration;
    }

    public static SpeechOptions defaults() {
        return new SpeechOptions(true, 0.65f, "config/voicecast/models/default", true, Calibration.DEFAULT);
    }
}
