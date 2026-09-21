package com.theo.voicecast.engine;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.engine.EngineSpec;
import com.theo.voicecast.config.VoiceCastConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sherpa-onnx Qwen3-ASR-0.6B offline text engine — the multilingual tier
 * (engine-swap hard cut, voicecast C1): one int8 model covering en/zh/ja/ko/
 * yue/de/fr/es/ru, open vocabulary, hotword-biased decoding. Decoding happens
 * on {@code finishUtterance} over the buffered utterance, exactly like the
 * SenseVoice engine's shape.
 *
 * <p><strong>Hotwords</strong> travel through
 * {@link OfflineQwen3AsrModelConfig.Builder#setHotwords(String)} (a comma list)
 * with greedy decoding — the only Java route lab-verified character-identical
 * with the Python pipeline (engine-swap L2 G4; {@code hotwordsFile} requires
 * modified_beam_search and drifts from the reference text). The hotword set is
 * the SESSION vocabulary's trigger aliases (rows whose id carries no
 * {@code .chant.} marker — i.e. what the current spell JSON roster pushed, in
 * the already language-routed projection), deduplicated and capped at
 * {@link #MAX_HOTWORDS} entries. Hotwords are baked into the native recognizer
 * at construction, so a live vocabulary change rebuilds it (rare — reload).
 *
 * <p><strong>Empty-transcript fallback</strong> (G-QWEN3 mandatory condition):
 * hotword-biased greedy decoding intermittently returns an empty transcript on
 * edge-of-model audio (L2/L2b: ~16 en rows, independent of hotword-set size),
 * while the SAME utterance decodes fine without hotwords (13/13 recovered).
 * A blank first decode therefore re-runs the utterance once through a shared
 * hotword-free recognizer before giving up.
 *
 * <p>The ONNX session is heavyweight (~2 GB RAM), so recognizers are cached per
 * (model dir, hotword set) across all sessions; decode is serialized inside the
 * native recognizer. Confidence is a constant 1.0; {@code templateScores}/
 * {@code ipaTokens} stay empty — adjudication lives in wizardreal's matchers.
 *
 * <p>Non-final by design: the two-pass fallback flow is scripted in tests via
 * a {@code decode} override; addons may likewise adapt decoding.
 */
public class SherpaQwen3Recognizer extends AbstractBufferedRecognizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCast");

    /** Hotword cap per session-language subset (G-QWEN3 condition ①). */
    static final int MAX_HOTWORDS = 100;

    /** "(modelDir)#(hotwords csv)" -> shared recognizer, one native load per set. */
    private static final Map<String, OfflineRecognizer> SHARED = new ConcurrentHashMap<>();

    private final EngineSpec spec;
    private short[] buffer = new short[32_000];
    private int buffered;
    private long utteranceStart;
    private volatile List<String> hotwords = List.of();

    public SherpaQwen3Recognizer(EngineSpec spec) {
        this.spec = spec;
    }

    /** Registry stub — real creation goes through EngineFamilies (needs EngineSpec). */
    public SherpaQwen3Recognizer() {
        this(EngineSpec.EMPTY);
    }

    @Override public String id() { return spec.engineId(); }
    @Override public String displayName() { return "sherpa Qwen3-ASR 0.6B (offline, 9-language)"; }

    // ------------------------------------------------------------- hotwords

    /**
     * Trigger aliases of the routed session vocabulary: rows WITHOUT the
     * {@code .chant.} marker (chant-line ids are pronunciation ids of chant
     * lines — the trigger rows are what biasing targets), aliases already
     * projected onto the session's language buckets by VocabularyRouter.
     * Dedup in encounter order, hard cap {@link #MAX_HOTWORDS}.
     */
    static List<String> extractHotwords(Collection<Pronunciation> vocabulary) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (vocabulary != null) {
            for (Pronunciation p : vocabulary) {
                if (p == null) continue;
                if (p.id() != null && p.id().contains(".chant.")) continue;
                for (String alias : p.aliases()) {
                    String t = alias == null ? "" : alias.trim();
                    if (!t.isEmpty()) out.add(t);
                }
            }
        }
        List<String> list = new ArrayList<>(out);
        if (list.size() > MAX_HOTWORDS) {
            LOGGER.warn("Session hotword subset capped at {} (had {}); trailing aliases dropped",
                    MAX_HOTWORDS, list.size());
            list = new ArrayList<>(list.subList(0, MAX_HOTWORDS));
        }
        return List.copyOf(list);
    }

    /** Comma-joined hotword list for {@code setHotwords} ("" when none). */
    static String hotwordsCsv(List<String> hotwords) {
        return String.join(", ", hotwords);
    }

    // -------------------------------------------------------------- engine

    private OfflineRecognizer shared(List<String> hotwords) {
        Path modelDir = spec.modelDir();
        String csv = hotwordsCsv(hotwords);
        String key = modelDir.toAbsolutePath().normalize() + "#" + csv;
        return SHARED.computeIfAbsent(key, k -> {
            try {
                OfflineQwen3AsrModelConfig.Builder q3 = OfflineQwen3AsrModelConfig.builder()
                        .setConvFrontend(modelDir.resolve(spec.option("conv_frontend", "conv_frontend.onnx")).toString())
                        .setEncoder(modelDir.resolve(spec.option("encoder", "encoder.int8.onnx")).toString())
                        .setDecoder(modelDir.resolve(spec.option("decoder", "decoder.int8.onnx")).toString())
                        .setTokenizer(modelDir.resolve(spec.option("tokenizer", "tokenizer")).toString())
                        .setMaxTotalLen(spec.intOption("max_total_len", 600))
                        .setMaxNewTokens(spec.intOption("max_new_tokens", 256));
                if (!csv.isEmpty()) q3.setHotwords(csv);
                OfflineRecognizerConfig cfg = OfflineRecognizerConfig.builder()
                        .setOfflineModelConfig(OfflineModelConfig.builder()
                                .setQwen3Asr(q3.build())
                                .setNumThreads(spec.intOption("num_threads", 8))
                                // sherpa's builder defaults debug=true; tie it to
                                // -Dvoicecast.verbose / /voicecast verbose
                                .setDebug(VoiceCastConfig.INSTANCE.verboseLogging)
                                .build())
                        .build();
                LOGGER.info("Loading shared Qwen3-ASR model from {} (hotwords={})", modelDir, hotwords.size());
                return new OfflineRecognizer(cfg);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load Qwen3-ASR model '" + k + "'", e);
            }
        });
    }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        hotwords = extractHotwords(vocabulary);
        shared(hotwords); // fail fast if model is broken
        super.start(options);
        LOGGER.info("sherpa Qwen3-ASR recognizer ready (engine={}, hotwords={})",
                spec.engineId(), hotwords.size());
    }

    @Override
    public synchronized void setVocabulary(Collection<Pronunciation> v) {
        super.setVocabulary(v);
        // Hotwords are baked into the native recognizer at construction; a live
        // vocabulary change rebuilds it only when already running (rare — reload).
        if (active && !spec.engineId().isEmpty()) {
            try {
                stop();                 // base stop clears the vocabulary list
                super.setVocabulary(v); // re-seed before start re-extracts hotwords
                start(new SpeechOptions(true, 0.65f, spec.modelDir().toString(), true));
            } catch (Exception e) {
                LOGGER.warn("hotwords reload failed for {}", spec.engineId(), e);
            }
        }
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
        float[] floats = new float[utterance.length];
        for (int i = 0; i < utterance.length; i++) {
            floats[i] = utterance[i] / 32768.0f;
        }
        try {
            OfflineRecognizer hot = shared(hotwords);
            String text = decode(hot, floats);
            if ((text == null || text.isBlank()) && !hotwords.isEmpty()) {
                // G-QWEN3 condition ②: one hotword-free re-decode of the same
                // utterance before giving up (L2b: recovers 13/13 lab empties).
                LOGGER.debug("[QWEN3] empty transcript with hotwords, re-decoding without");
                text = decode(shared(List.of()), floats);
            }
            if (text == null || text.isBlank()) return;
            emit(text.trim().toLowerCase(Locale.ROOT), List.of(), 1.0f, startMs);
        } catch (Throwable t) {
            LOGGER.warn("Qwen3-ASR decode failed (engine={})", spec.engineId(), t);
        }
    }

    /** One offline decode pass over the utterance (16 kHz true capture rate).
     *  Package-private so tests can script the two-pass fallback flow. */
    String decode(OfflineRecognizer recognizer, float[] floats) {
        var stream = recognizer.createStream();
        try {
            stream.acceptWaveform(floats, 16_000);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText();
        } finally {
            stream.release();
        }
    }

    private void ensureCapacity(int needed) {
        if (buffer.length >= needed) return;
        int cap = buffer.length;
        while (cap < needed) cap *= 2;
        short[] grown = new short[cap];
        System.arraycopy(buffer, 0, grown, 0, buffered);
        buffer = grown;
    }

    /** Test hook: the active hotword list. */
    List<String> hotwordsForTest() { return hotwords; }
}
