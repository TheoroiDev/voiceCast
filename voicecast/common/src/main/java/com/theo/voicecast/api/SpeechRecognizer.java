package com.theo.voicecast.api;

/**
 * A speech recognition engine. Implementations may wrap sherpa-onnx, ONNX Runtime,
 * Whisper, an external library mod, or any future backend.
 *
 * <p>All methods must be safe to call from the Minecraft client thread;
 * implementations should off-load audio work to their own threads.
 */
public interface SpeechRecognizer {

    /** Stable id, e.g. {@code "qwen3-asr-0.6b-int8"} or {@code "zipa-ipa"}. */
    String id();

    /** Human-readable name shown in the config UI. */
    String displayName();

    /** Initialize native resources and load the model. */
    void start(SpeechOptions options) throws Exception;

    /** Stop listening and release native resources. */
    void stop();

    boolean isActive();

    /** Replace the active vocabulary (semantic contract v2: the one push
     *  entry point; engines derive their own shapes from it). */
    void setVocabulary(SessionVocabulary vocabulary);

    /**
     * Route results to a specific target instead of the global event bus
     * (server sessions use this). Default: ignore (implementations that only
     * support the global bus keep their existing behavior).
     */
    default void setResultSink(java.util.function.Consumer<RecognitionResult> sink) {}

    /**
     * Feed a block of 16 kHz, 16-bit, mono signed PCM samples. Called on the
     * mic thread, so implementations should return quickly and decode on a
     * worker thread when needed.
     */
    default void acceptPcm(short[] samples, int offset, int length) {}

    /**
     * Flush any buffered audio and produce a final result for the current
     * utterance. Called when the user releases PTT so buffered engines
     * that wait for an endpoint (silence) still return their last result.
     */
    default void finishUtterance() {}

    /** Called every client tick while a world is loaded. */
    default void tick() {}

    /**
     * Diagnostics of the last adjudication (semantic contract v2): the
     * engine-internal numbers — CTC posteriors, margin gap, rejection
     * reason — outside the result contract. Null when the engine has no
     * diagnostics or nothing was adjudicated yet.
     */
    default RecognitionDiagnostics lastDiagnostics() { return null; }
}
