package com.theo.voicecast.engine;

import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.engine.EngineSpec;
import com.theo.voicecast.config.VoiceCastConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * sherpa-onnx streaming zipformer (transducer) text engine — the vosk
 * replacement for short trigger words (voiceCast#42). Open vocabulary by
 * nature: the model transcribes whatever it hears and {@code SpellMatcher}
 * does the adjudication, so the vosk lexicon wall cannot recur. Spell words
 * are injected as hotwords (transducer {@code modified_beam_search}), which
 * boosts their recognition without constraining the output.
 *
 * <p>Data flow: {@code acceptPcm} feeds 16 kHz mono frames into an
 * {@link OnlineStream}; {@code finishUtterance} finishes the input, drains the
 * decode loop and emits one final result, then opens the next stream. Partial
 * results are intentionally not emitted (v1: final-only, matching the vosk
 * engine's observable behavior).
 *
 * <p>Confidence is a constant 1.0 — streaming transducers expose no reliable
 * word-level confidence, and cast power no longer consumes recognition
 * confidence (0.4.0 power = chant tier × learning).
 */
public final class SherpaStreamingRecognizer implements SpeechRecognizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCast");

    private final EngineSpec spec;
    private final List<Pronunciation> vocabulary = new ArrayList<>();
    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private java.util.function.Consumer<RecognitionResult> sink;
    private volatile boolean active;
    private long utteranceStartMs;

    public SherpaStreamingRecognizer(EngineSpec spec) {
        this.spec = spec;
    }

    /** Registry stub — real creation goes through EngineFamilies (needs EngineSpec). */
    public SherpaStreamingRecognizer() {
        this(EngineSpec.EMPTY);
    }

    @Override public String id() { return spec.engineId(); }
    @Override public String displayName() { return "sherpa streaming (" + spec.type() + ")"; }

    @Override
    public void setResultSink(java.util.function.Consumer<RecognitionResult> sink) {
        this.sink = sink;
    }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        Path modelDir = Path.of(options.modelPath());
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(modelDir.resolve(spec.option("encoder", "encoder.onnx")).toString())
                .setDecoder(modelDir.resolve(spec.option("decoder", "decoder.onnx")).toString())
                .setJoiner(modelDir.resolve(spec.option("joiner", "joiner.onnx")).toString())
                .build();
        String modelingUnit = spec.option("modeling_unit", "cjkchar+bpe");
        Path bpeVocab = modelDir.resolve(spec.option("bpe_vocab", "bpe.vocab"));
        // Hotwords are decided first: cjkchar+bpe hotword encoding needs the
        // model's bpe vocabulary — without it the native recognizer creation
        // fails outright, so writeHotwords degrades to no-hotwords instead.
        Path hotwords = writeHotwords(modelingUnit, bpeVocab);

        OnlineModelConfig.Builder model = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(modelDir.resolve(spec.option("tokens", "tokens.txt")).toString())
                .setNumThreads(spec.intOption("num_threads", 2))
                .setModelingUnit(modelingUnit)
                // sherpa's builder defaults debug=true (dumps config/state);
                // tie it to the dev verbosity channel instead (-PvoicecastVerbose
                // → -Dvoicecast.verbose, or /voicecast verbose in game)
                .setDebug(VoiceCastConfig.INSTANCE.verboseLogging);
        if (hotwords != null && modelingUnit.contains("bpe")) {
            model.setBpeVocab(bpeVocab.toString());
        }

        OnlineRecognizerConfig.Builder cfg = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(model.build())
                .setDecodingMethod(spec.option("decoding_method", "modified_beam_search"))
                .setEnableEndpoint(false); // utterance lifecycle is driven by finishUtterance
        if (hotwords != null) {
            cfg.setHotwordsFile(hotwords.toString());
            cfg.setHotwordsScore((float) doubleOption("hotwords_score", 1.5));
        }

        recognizer = new OnlineRecognizer(cfg.build());
        stream = recognizer.createStream();
        active = true;
        LOGGER.info("sherpa streaming recognizer ready (engine={}, hotwords={})",
                spec.engineId(), hotwords != null);
    }

    /**
     * Spell aliases as hotword entries (one per line) in a stable temp path.
     * Returns null when there is nothing to boost or when the modeling unit
     * needs a bpe vocabulary that is not present (hotwords would break the
     * native recognizer creation — plain open-vocabulary decoding still works).
     */
    private Path writeHotwords(String modelingUnit, Path bpeVocab) {
        List<String> aliases = new ArrayList<>();
        for (Pronunciation p : vocabulary) {
            for (String a : p.aliases()) {
                String trimmed = a.trim();
                if (!trimmed.isEmpty() && !aliases.contains(trimmed)) aliases.add(trimmed);
            }
        }
        if (aliases.isEmpty()) return null;
        if (modelingUnit.contains("bpe") && !Files.isRegularFile(bpeVocab)) {
            LOGGER.warn("modeling_unit '{}' hotwords require {} (missing) - hotwords disabled, "
                    + "open-vocabulary decoding continues", modelingUnit, bpeVocab);
            return null;
        }
        try {
            Path file = Path.of(System.getProperty("java.io.tmpdir"),
                    "voicecast-hotwords-" + Integer.toUnsignedString(spec.engineId().hashCode()) + ".txt");
            Files.write(file, aliases, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            LOGGER.warn("hotwords file write failed (hotwords disabled)", e);
            return null;
        }
    }

    @Override
    public synchronized void setVocabulary(Collection<Pronunciation> v) {
        vocabulary.clear();
        if (v != null) vocabulary.addAll(v);
        // Hotwords apply at recognizer construction; a live vocabulary change
        // rebuilds the recognizer only when already running (rare — reload).
        if (active && recognizer != null) {
            try {
                stop();
                start(new SpeechOptions(true, 0.65f, spec.modelDir().toString(), true));
            } catch (Exception e) {
                LOGGER.warn("hotwords reload failed for {}", spec.engineId(), e);
            }
        }
    }

    @Override
    public synchronized void acceptPcm(short[] samples, int offset, int length) {
        if (!active || recognizer == null || stream == null) return;
        float[] floats = new float[length];
        for (int i = 0; i < length; i++) {
            floats[i] = samples[offset + i] / 32768.0f;
        }
        stream.acceptWaveform(floats, length);
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
        emitPartialIfChanged();
    }

    private String lastPartial = "";

    private void emitPartialIfChanged() {
        String text = recognizer.getResult(stream).getText();
        if (text == null || text.isBlank() || text.equals(lastPartial)) return;
        lastPartial = text;
        RecognitionResult r = RecognitionResult.partial(text.trim(), List.of(), 1.0f);
        java.util.function.Consumer<RecognitionResult> s = sink;
        if (s != null) s.accept(r);
    }

    @Override
    public synchronized void finishUtterance() {
        if (!active || recognizer == null || stream == null) return;
        stream.inputFinished();
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
        String text = recognizer.getResult(stream).getText();
        long startMs = utteranceStartMs;
        utteranceStartMs = 0;
        lastPartial = "";
        // Recycle the stream for the next utterance BEFORE emitting so the sink
        // can immediately start a new chant without clobbering our state.
        stream.release();
        stream = recognizer.createStream();
        if (text == null || text.isBlank()) return;
        RecognitionResult r = RecognitionResult.finality(
                text.trim().toLowerCase(Locale.ROOT), List.of(), 1.0f, startMs, java.util.Map.of());
        java.util.function.Consumer<RecognitionResult> s = sink;
        if (s != null) s.accept(r);
    }

    @Override
    public synchronized void stop() {
        active = false;
        if (stream != null) { try { stream.release(); } catch (Throwable ignored) {} stream = null; }
        if (recognizer != null) { try { recognizer.release(); } catch (Throwable ignored) {} recognizer = null; }
    }

    @Override
    public boolean isActive() { return active; }

    private double doubleOption(String key, double fallback) {
        try {
            return Double.parseDouble(spec.option(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
