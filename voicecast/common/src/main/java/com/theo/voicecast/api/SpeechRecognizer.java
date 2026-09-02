package com.theo.voicecast.api;

import java.util.Collection;

/**
 * A speech recognition engine. Implementations may wrap Vosk, sherpa-onnx,
 * Whisper, an external library mod, or any future backend.
 *
 * <p>All methods must be safe to call from the Minecraft client thread;
 * implementations should off-load audio work to their own threads.
 */
public interface SpeechRecognizer {

    /** Stable id, e.g. {@code "sherpa-ipa"} or {@code "vosk-en"}. */
    String id();

    /** Human-readable name shown in the config UI. */
    String displayName();

    /** Initialize native resources and load the model. */
    void start(SpeechOptions options) throws Exception;

    /** Stop listening and release native resources. */
    void stop();

    boolean isActive();

    /** Replace the active vocabulary (supported by grammar-based engines). */
    void setVocabulary(Collection<Pronunciation> vocabulary);

    /**
     * Feed a block of 16 kHz, 16-bit, mono signed PCM samples. Called on the
     * mic thread, so implementations should return quickly and decode on a
     * worker thread when needed.
     */
    default void acceptPcm(short[] samples, int offset, int length) {}

    /**
     * Flush any buffered audio and produce a final result for the current
     * utterance. Called when the user releases PTT so Vosk-like engines
     * that wait for an endpoint (silence) still return their last result.
     */
    default void finishUtterance() {}

    /** Called every client tick while a world is loaded. */
    default void tick() {}
}
