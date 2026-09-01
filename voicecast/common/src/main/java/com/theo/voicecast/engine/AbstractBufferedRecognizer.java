package com.theo.voicecast.engine;

import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognitionFinalEvent;
import com.theo.voicecast.api.event.RecognitionPartialEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Base class for recognizers that buffer utterances and emit them. Handles
 * PCM ring buffering so subclasses can focus on decoding.
 *
 * <p>Subclasses must:
 * <ul>
 *     <li>Call {@link #emit(String, List, float, long)} when an utterance is final.</li>
 *     <li>Implement {@link #decode(short[], int, int)} to feed the engine.</li>
 * </ul>
 */
public abstract class AbstractBufferedRecognizer implements SpeechRecognizer {
    /** Routes results instead of posting to the global VoiceCastEvents bus (server sessions). */
    public interface ResultSink {
        void onResult(RecognitionResult result);
    }

    protected final List<Pronunciation> vocabulary = new ArrayList<>();
    protected volatile boolean active;
    protected long utteranceStartMs;
    private volatile ResultSink sink;

    /** Set a per-instance result target; null restores the global event bus. */
    public void setResultSink(ResultSink s) { this.sink = s; }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        active = true;
    }

    @Override
    public synchronized void stop() {
        active = false;
        vocabulary.clear();
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public synchronized void setVocabulary(Collection<Pronunciation> v) {
        vocabulary.clear();
        if (v != null) vocabulary.addAll(v);
        onVocabularyChanged();
    }

    protected void onVocabularyChanged() {}

    @Override
    public final void acceptPcm(short[] samples, int offset, int length) {
        if (!active) return;
        if (utteranceStartMs == 0) utteranceStartMs = System.currentTimeMillis();
        try {
            decode(samples, offset, length);
        } catch (Throwable t) {
            // decoder errors must not kill the mic thread
            onError(t);
        }
    }

    /**
     * Called when the user releases PTT. Subclasses should flush any
     * buffered audio and emit a final result.
     */
    @Override
    public void finishUtterance() {
        // default no-op; subclasses override
    }

    protected abstract void decode(short[] samples, int offset, int length) throws Exception;

    protected void onError(Throwable t) {
        com.theo.voicecast.VoiceCast.LOGGER.warn("{} decode error", id(), t);
    }

    /** Emit a final result to the per-instance sink, or the global bus by default. */
    protected void emit(String text, List<String> ipa, float confidence, long startMs) {
        emit(text, ipa, confidence, startMs, java.util.Map.of());
    }

    /** Emit a final result carrying optional CTC vocabulary scores (pronunciation id -> [0,1]). */
    protected void emit(String text, List<String> ipa, float confidence, long startMs,
                        java.util.Map<String, Float> templateScores) {
        RecognitionResult r = RecognitionResult.finality(text, ipa, confidence, startMs, templateScores);
        ResultSink s = sink;
        if (s != null) s.onResult(r);
        else VoiceCastEvents.post(new RecognitionFinalEvent(r));
    }

    protected void emitPartial(String text, List<String> ipa, float confidence) {
        RecognitionResult r = RecognitionResult.partial(text, ipa, confidence);
        ResultSink s = sink;
        if (s != null) s.onResult(r);
        else VoiceCastEvents.post(new RecognitionPartialEvent(r));
    }
}
