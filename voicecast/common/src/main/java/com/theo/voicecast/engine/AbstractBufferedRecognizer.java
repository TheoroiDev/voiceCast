package com.theo.voicecast.engine;

import com.theo.voicecast.api.RecognitionDiagnostics;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognitionFinalEvent;
import com.theo.voicecast.api.event.RecognitionPartialEvent;
import com.theo.voicecast.match.UtteranceAdjudicator;

import java.util.List;
import java.util.Map;

/**
 * Base class for recognizers that buffer utterances and emit them. Handles
 * PCM ring buffering so subclasses can focus on decoding.
 *
 * <p>Semantic contract v2 (C1b): final results are ADJUDICATED — the base
 * runs the {@link UtteranceAdjudicator} over the session vocabulary and the
 * subclass's evidence (text line, phoneme tokens, CTC posteriors + margin)
 * before emitting, so every consumer sees a {@link RecognitionResult} with a
 * voicecast-owned {@link com.theo.voicecast.api.Decision}. Engine-internal
 * numbers stay in the diagnostics accessor.
 *
 * <p>Subclasses must:
 * <ul>
 *     <li>Call {@link #emitAdjudicated} when an utterance is final.</li>
 *     <li>Implement {@link #decode(short[], int, int)} to feed the engine.</li>
 * </ul>
 */
public abstract class AbstractBufferedRecognizer implements SpeechRecognizer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoiceCast");

    /** Routes results instead of posting to the global VoiceCastEvents bus (server sessions). */
    public interface ResultSink {
        void onResult(RecognitionResult result);
    }

    protected volatile SessionVocabulary vocabulary = SessionVocabulary.EMPTY;
    protected volatile boolean active;
    protected long utteranceStartMs;
    private volatile ResultSink sink;
    private volatile RecognitionDiagnostics lastDiagnostics;

    @Override
    public void setResultSink(java.util.function.Consumer<RecognitionResult> sink) {
        this.sink = sink == null ? null : sink::accept;
    }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        this.options = options;
        active = true;
    }

    @Override
    public synchronized void stop() {
        active = false;
        vocabulary = SessionVocabulary.EMPTY;
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public synchronized void setVocabulary(SessionVocabulary v) {
        vocabulary = v == null ? SessionVocabulary.EMPTY : v;
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
        LOGGER.warn("{} decode error", id(), t);
    }

    @Override
    public RecognitionDiagnostics lastDiagnostics() {
        return lastDiagnostics;
    }

    /**
     * Adjudicate one final utterance against the session vocabulary and emit
     * it. {@code ctcPosteriors}/{@code margin} are CTC-line evidence (null on
     * text-only engines); {@code language} is the engine's language bucket
     * projection ("" when language-agnostic).
     */
    protected void emitAdjudicated(String utteranceText, List<String> ipaTokens, long startMs,
                                   Map<String, Float> ctcPosteriors,
                                   UtteranceAdjudicator.MarginInfo margin, String language) {
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                vocabulary.entries(), options == null ? null : options.calibration(),
                utteranceText, ipaTokens, ctcPosteriors, margin);
        lastDiagnostics = a.diagnostics();
        RecognitionResult r = RecognitionResult.finality(utteranceText,
                ipaTokens == null ? "" : String.join(" ", ipaTokens), language,
                a.decision(), a.spellId(), a.pronId(), a.score(), a.alternatives(), startMs);
        ResultSink s = sink;
        if (s != null) s.onResult(r);
        else VoiceCastEvents.post(new RecognitionFinalEvent(r));
    }

    /** HUD-only partial (no decision). */
    protected void emitPartial(String utteranceText, String ipa) {
        RecognitionResult r = RecognitionResult.partial(utteranceText, ipa);
        ResultSink s = sink;
        if (s != null) s.onResult(r);
        else VoiceCastEvents.post(new RecognitionPartialEvent(r));
    }

    /** SpeechOptions captured at start (calibration source). */
    protected SpeechOptions options;
}
