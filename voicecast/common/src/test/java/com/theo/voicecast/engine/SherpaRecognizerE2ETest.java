package com.theo.voicecast.engine;

import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.engine.EngineSpec;
import com.theo.voicecast.model.ModelConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual end-to-end checks against the REAL workspace models (engine-swap C1:
 * the E2E now covers the new two-engine lineup — the ZIPA phoneme engine has
 * its own decode-parity E2E in {@link ZipaDecodeE2ETest}, this class covers
 * the sherpa Qwen3-ASR engine). Opt-in by model presence:
 * needs {@code resources/models/qwen3-asr-0.6b-int8} plus the probe wavs.
 *
 * <p>Covers the G-QWEN3 mandatory conditions on the real engine: hotwords
 * travel through {@code setHotwords} (greedy), the empty-transcript fallback
 * re-decodes without hotwords, and a whitenoise+hotwords probe must not
 * produce any vocabulary hit (non-speech guardrail — the existing vocabulary
 * gate adjudicates, no new machinery).
 */
class SherpaRecognizerE2ETest {

    private static final List<SessionVocabulary.Entry> VOCAB = List.of(
            new SessionVocabulary.Entry("e2e-fulmen", List.of(), List.of("fulmen", "lightning"), null, null),
            new SessionVocabulary.Entry("e2e-aegis", List.of(), List.of("aegis", "shield"), null, null),
            new SessionVocabulary.Entry("e2e-explosion", List.of(), List.of("explosion", "burst"), null, null));

    private static Path modelDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve("resources/models/qwen3-asr-0.6b-int8");
            if (Files.isRegularFile(cand.resolve("encoder.int8.onnx"))) return cand;
        }
        return null;
    }

    private static SherpaQwen3Recognizer recognizer(Path model) throws Exception {
        ModelConfig config = ModelConfig.load(Files.createTempDirectory("voicecast-e2e-cfg"));
        EngineSpec spec = new EngineSpec(
                config.familyFor("qwen3-asr-0.6b-int8"),
                "qwen3-asr-0.6b-int8",
                model,
                config.languagesFor("qwen3-asr-0.6b-int8"),
                config.optionsFor("qwen3-asr-0.6b-int8"));
        SherpaQwen3Recognizer recognizer = new SherpaQwen3Recognizer(spec);
        recognizer.setVocabulary(new SessionVocabulary(VOCAB));
        recognizer.start(new SpeechOptions(true, 0.65f, model.toString(), false, null));
        return recognizer;
    }

    private static short[] pcm(Path wav) throws Exception {
        float[] wave = TestWav.readMono16k(wav);
        short[] pcm = new short[wave.length];
        for (int i = 0; i < wave.length; i++) {
            pcm[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(wave[i] * 32768.0f)));
        }
        return pcm;
    }

    @Test
    void startsWithHotwordsAndSilenceProducesNoResult() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/qwen3-asr-0.6b-int8 not found");
        Path silence = KaldiFbankTest.findWorkspaceFile("build/engine_swap/probe_wav/silence3s.wav");
        assumeTrue(silence != null, "probe wav not found");

        SherpaQwen3Recognizer recognizer = recognizer(model);
        assertTrue(recognizer.isActive(), "recognizer must be active after start");
        assertEquals(6, recognizer.hotwordsForTest().size(), "hotwords resolved from the vocabulary");
        AtomicReference<RecognitionResult> received = new AtomicReference<>();
        recognizer.setResultSink(received::set);

        recognizer.acceptPcm(pcm(silence), 0, pcm(silence).length);
        recognizer.finishUtterance();
        Thread.sleep(1500); // silence decodes empty twice (hotwords, then fallback)
        recognizer.stop();
        assertNull(received.get(), "silence must not produce a transcript");
    }

    @Test
    void whitenoiseWithHotwordsProducesNoVocabularyHit() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/qwen3-asr-0.6b-int8 not found");
        Path noise = KaldiFbankTest.findWorkspaceFile("build/engine_swap/probe_wav/whitenoise3s.wav");
        assumeTrue(noise != null, "probe wav not found");

        SherpaQwen3Recognizer recognizer = recognizer(model);
        AtomicReference<RecognitionResult> received = new AtomicReference<>();
        recognizer.setResultSink(received::set);
        recognizer.acceptPcm(pcm(noise), 0, pcm(noise).length);
        recognizer.finishUtterance();
        for (int i = 0; i < 100 && received.get() == null; i++) Thread.sleep(100);
        recognizer.stop();
        RecognitionResult r = received.get();
        if (r != null) { // any transcript at all must not hit the pushed vocabulary
            for (SessionVocabulary.Entry p : VOCAB) {
                for (String alias : p.aliases()) {
                    String text = r.utteranceText();
                    assertTrue(!text.toLowerCase(java.util.Locale.ROOT).contains(alias.toLowerCase(
                            java.util.Locale.ROOT)), "whitenoise transcript must not alias-hit: " + text);
                }
            }
        }
    }

    /** Scripted two-pass flow: hotword decode empty -> hotword-free fallback result. */
    @Test
    void emptyHotwordTranscriptFallsBackToHotwordFreeDecode() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/qwen3-asr-0.6b-int8 not found");
        Path wav = KaldiFbankTest.findWorkspaceFile("lab/corpus/fulmen__trigger__en__fulmen__en_us_arianeural__+15.wav");
        assumeTrue(wav != null, "lab corpus not found");

        EngineSpec spec = new EngineSpec("sherpa-qwen3", "qwen3-asr-0.6b-int8", model,
                List.of(), java.util.Map.of());
        Scripted recognizer = new Scripted(spec);
        recognizer.setVocabulary(new SessionVocabulary(VOCAB));
        recognizer.start(new SpeechOptions(true, 0.65f, model.toString(), false, null));
        AtomicReference<RecognitionResult> received = new AtomicReference<>();
        recognizer.setResultSink(received::set);
        recognizer.acceptPcm(pcm(wav), 0, pcm(wav).length);
        recognizer.finishUtterance();
        for (int i = 0; i < 100 && received.get() == null; i++) Thread.sleep(100);
        recognizer.stop();
        RecognitionResult r = received.get();
        assertNotNull(r, "fallback transcript must be emitted");
        assertEquals("aegis.", r.utteranceText(), "fallback re-decode result");
        assertEquals(2, recognizer.decodeCalls, "two passes: hotword decode, then hotword-free fallback");
    }

    /** Records the decode passes; the recognizer still exercises real loads. */
    static class Scripted extends SherpaQwen3Recognizer {
        int decodeCalls;
        Scripted(EngineSpec spec) { super(spec); }
        @Override String decode(com.k2fsa.sherpa.onnx.OfflineRecognizer rec, float[] floats) {
            decodeCalls++;
            return decodeCalls == 1 ? "" : "aegis."; // 1st (hotword) empty, fallback recovers
        }
    }
}
