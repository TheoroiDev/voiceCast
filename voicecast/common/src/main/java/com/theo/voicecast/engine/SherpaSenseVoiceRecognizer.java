package com.theo.voicecast.engine;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.engine.EngineSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sherpa-onnx SenseVoice offline text engine — the short-utterance
 * high-accuracy tier (voiceCast#42): one int8 model covering zh/en/ja/ko, open
 * vocabulary (no lexicon wall — literary chant lines are transcribable), RTF
 * 0.04–0.06 on CPU. Decoding happens on {@code finishUtterance} over the
 * buffered utterance, exactly like the IPA engine's shape.
 *
 * <p>The ONNX session is heavyweight (RAM in the hundreds of MB), so exactly
 * one {@link OfflineRecognizer} per model directory is shared across all
 * sessions; decode is serialized on the shared instance (RTF ≪ 1 keeps the
 * queue short). {@code setVocabulary} is a no-op — adjudication lives in
 * wizardreal's matchers, fed with plain transcribed text.
 *
 * <p>Confidence is a constant 1.0 (SenseVoice exposes no calibrated word-level
 * confidence we consume); {@code templateScores}/{@code ipaTokens} stay empty.
 */
public final class SherpaSenseVoiceRecognizer extends AbstractBufferedRecognizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCast");

    /** modelDir -> shared recognizer (one ONNX session per model, all sessions). */
    private static final Map<String, OfflineRecognizer> SHARED = new ConcurrentHashMap<>();

    private final EngineSpec spec;
    private short[] buffer = new short[32_000];
    private int buffered;
    private long utteranceStart;

    public SherpaSenseVoiceRecognizer(EngineSpec spec) {
        this.spec = spec;
    }

    @Override public String id() { return spec.engineId(); }
    @Override public String displayName() { return "sherpa SenseVoice (offline, 5-language)"; }

    private static OfflineRecognizer shared(Path modelDir, EngineSpec spec) {
        return SHARED.computeIfAbsent(modelDir.toAbsolutePath().normalize().toString(), dir -> {
            try {
                OfflineSenseVoiceModelConfig sv = OfflineSenseVoiceModelConfig.builder()
                        .setModel(modelDir.resolve(spec.option("model", "model.int8.onnx")).toString())
                        .setLanguage(spec.option("language", "auto"))
                        .setInverseTextNormalization(
                                Boolean.parseBoolean(spec.option("itn", "true")))
                        .build();
                OfflineRecognizerConfig cfg = OfflineRecognizerConfig.builder()
                        .setOfflineModelConfig(OfflineModelConfig.builder()
                                .setSenseVoice(sv)
                                .setTokens(modelDir.resolve(spec.option("tokens", "tokens.txt")).toString())
                                .setNumThreads(spec.intOption("num_threads", 2))
                                .build())
                        .build();
                LOGGER.info("Loading shared SenseVoice model from {}", modelDir);
                return new OfflineRecognizer(cfg);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load SenseVoice model '" + dir + "'", e);
            }
        });
    }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        shared(Path.of(options.modelPath()), spec); // fail fast if model is broken
        super.start(options);
        LOGGER.info("sherpa SenseVoice recognizer ready (engine={})", spec.engineId());
    }

    @Override
    public synchronized void setVocabulary(java.util.Collection<Pronunciation> v) {
        // Open vocabulary: entries are not pushed into the engine. Kept for the
        // interface contract only.
    }

    @Override
    protected void decode(short[] samples, int offset, int length) {
        if (utteranceStart == 0) utteranceStart = System.currentTimeMillis();
        ensureCapacity(buffered + length);
        System.arraycopy(samples, offset, buffer, buffered, length);
        buffered += length;
    }

    @Override
    public synchronized void finishUtterance() {
        if (buffered == 0) return;
        long startMs = utteranceStart;
        utteranceStart = 0;
        short[] utterance = new short[buffered];
        System.arraycopy(buffer, 0, utterance, 0, buffered);
        buffered = 0;
        if (utterance.length < 4_000) { // <250 ms: noise, not an utterance
            return;
        }
        OfflineRecognizer shared = shared(modelDir(), spec);
        float[] floats = new float[utterance.length];
        for (int i = 0; i < utterance.length; i++) {
            floats[i] = utterance[i] / 32768.0f;
        }
        try {
            var stream = shared.createStream();
            stream.acceptWaveform(floats, floats.length);
            shared.decode(stream);
            String text = shared.getResult(stream).getText();
            stream.release();
            if (text == null || text.isBlank()) return;
            emit(text.trim().toLowerCase(Locale.ROOT), List.of(), 1.0f, startMs);
        } catch (Throwable t) {
            LOGGER.warn("SenseVoice decode failed (engine={})", spec.engineId(), t);
        }
    }

    private Path modelDir() {
        return Path.of(spec.modelDir().toString());
    }

    private void ensureCapacity(int needed) {
        if (buffer.length >= needed) return;
        int cap = buffer.length;
        while (cap < needed) cap *= 2;
        short[] grown = new short[cap];
        System.arraycopy(buffer, 0, grown, 0, buffered);
        buffer = grown;
    }
}
