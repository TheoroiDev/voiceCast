package com.theo.voicecast.engine;

import com.theo.voicecast.api.Decision;
import com.theo.voicecast.api.RecognitionDiagnostics;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.SpeechOptions;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-machine machine gates for the ZIPA recognition chain on adversarial
 * and Japanese material (engine-swap R1 refine, H2): the anemos zh
 * near-homophone negative from the lab corpus must NOT false-positive on the
 * production chain — the CTC margin gate suppresses the tied top1 (the
 * pre-margin posterior would have cleared the 0.10 forward threshold) and
 * the adjudicator rules AMBIGUOUS — and ja chant lines must close the loop
 * through template mapping -> CTC forward -> adjudication (EXACT on the
 * pushed template).
 *
 * <p>All expected values are pinned from a real-machine run (model
 * sha256 e79c5ec3…, icu4j 71.1 on the test classpath — the Phonetics layer
 * differs without it). Wavs stay in the lab corpus (never copied into the
 * repository): the tests skip when the workspace checkout has no
 * {@code resources/models/zipa-ipa} or no lab corpus.
 */
class ZipaMarginGateE2ETest {

    /** Posterior tolerance: ONNX CPU decode is deterministic for a fixed
     *  model+runtime, but intra-op parallelism can shift the last ulps. */
    private static final float EPS = 0.01f;

    private static Path modelDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve("resources/models/zipa-ipa");
            if (Files.isRegularFile(cand.resolve("model.int8.onnx"))) return cand;
        }
        return null;
    }

    private static Path wav(String relative) {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve(relative);
            if (Files.isRegularFile(cand)) return cand;
        }
        return null;
    }

    /**
     * The anemos vocabulary in production shape (spell JSON templates + the
     * real pronunciation id scheme): trigger row, both zh chant variants
     * (first/last lines are adjudicator surfaces) and the ja cast line.
     */
    private static List<SessionVocabulary.Entry> anemosVocabulary() {
        List<SessionVocabulary.Entry> entries = new ArrayList<>();
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos",
                List.of("ʈʂən kʰʊŋ ʐən", "a nɛ mo sɯ"), List.of(),
                Map.of("en", List.of("anemos"), "zh", List.of("真空刃"), "ja", List.of("アネモス")), null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.0:0",
                List.of("ʈʂən kʰʊŋ ʐən a ti tʰɪŋ ʈʂɑŋ kʰʊŋ"), List.of("真空刃啊，谛听长空"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.0:1",
                List.of("i kɑŋ fəŋ ʈʂɨ mɪŋ ɪŋ ʈʂaʊ"), List.of("以罡风之名应召"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.0:2",
                List.of("ʈʂɨ tɕʰɪŋ fəŋ ʐu wɔ jɛn"), List.of("织清风入我言"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.0:3",
                List.of("fu ʈʂɑŋ kʰʊŋ y wɔ i"), List.of("缚长空于我意"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.0:4",
                List.of("ʈʂən kʰʊŋ ʐən"), List.of("真空刃"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.1:0",
                List.of("ʈʂən kʰʊŋ ʐən a ti tʰɪŋ ʈʂɑŋ kʰʊŋ"), List.of("真空刃啊，谛听长空"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.1:1",
                List.of("tɕʰɪŋ fəŋ sweɪ u ʂəŋ ɚ ɕɪŋ"), List.of("清风随吾声而醒"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.1:2",
                List.of("ʈʂɑŋ kʰʊŋ ʂɨ tɤ u mɪŋ"), List.of("长空识得吾名"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.zh.1:3",
                List.of("laɪ ɕi u tɕi ʈʂɨ kɑŋ fəŋ"), List.of("来兮，无羁之罡风"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:anemos.chant.ja.0:4",
                List.of("a nɛ mo sɯ"), List.of("アネモス"), null, null));
        return entries;
    }

    /**
     * The lab negative "今天阳光真好" (今天阳光真好 is engineered as an anemos zh
     * near-homophone of 真空刃; lab/corpus/index_negative.tsv neg__zh__x__ rows)
     * through the PRODUCTION recognizer with the anemos vocabulary pushed:
     * the CTC margin gate must suppress it — no EXACT, no NEAR, AMBIGUOUS.
     *
     * <p>Pinned real-machine values: the pre-margin top1 posterior 0.3388 is
     * TIED with top2 (gap 0.0000 &lt; margin 0.02), would have passed the 0.10
     * forward threshold, every post-margin score is zeroed, and the text tier
     * does not fire on the phoneme-token transcript (icu4j present).
     */
    @Test
    void anemosZhNearHomophoneIsMarginSuppressed() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/zipa-ipa not found");
        Path neg = wav("lab/corpus/negative/neg__zh__x__+0.wav");
        assumeTrue(neg != null, "lab negative corpus wav not present");

        ZipaPhonemeRecognizer recognizer = new ZipaPhonemeRecognizer();
        recognizer.setVocabulary(new SessionVocabulary(anemosVocabulary()));
        RecognitionResult r = run(recognizer, model, neg);

        // The machine gate: no false positive of any acceptance tier.
        assertEquals(Decision.AMBIGUOUS, r.decision(),
                "margin-tied top1 must rule AMBIGUOUS, got " + r.decision());
        assertEquals("wizardreal:anemos.chant.zh.0:0", r.pronId());

        // MarginInfo evidence, pinned from the real run: a tied top1 that the
        // margin gate rejected before the CTC tier could fire.
        RecognitionDiagnostics diag = recognizer.lastDiagnostics();
        assertNotNull(diag);
        assertTrue(diag.ctcPresent(), "CTC scoring ran (vocabulary pushed)");
        assertTrue(diag.marginRejected(), "margin gate must reject the tied top1");
        assertEquals(0.3388f, diag.marginTop1(), EPS);
        assertEquals(0.3388f, diag.marginTop2(), EPS);
        assertEquals(diag.marginTop1(), diag.marginTop2(), EPS,
                "top1/top2 tie (gap 0.0000 < margin) is the suppression trigger");
        assertTrue(diag.marginTop1() >= 0.10f,
                "the suppressed top1 would have passed the 0.10 forward threshold");
        assertNotNull(diag.rejectionReason());
        assertTrue(diag.rejectionReason().startsWith("ctc_margin:"),
                "rejection reason names the margin gate, got: " + diag.rejectionReason());
        // The margin rejection zeroes every emitted score (never a partial
        // suppression) — no template may inherit the win.
        for (float v : diag.templateScores().values()) {
            assertEquals(0.0f, v, 0f);
        }
        assertEquals(diag.marginTop1(), r.score(), EPS,
                "AMBIGUOUS carries the pre-margin top1 as evidence");
        recognizer.stop();
    }

    /**
     * ja chant lines close the loop (l1_rows.json ja rows, real wavs): the
     * pushed template maps into the ZIPA emission space, the CTC forward
     * pass scores it decisively above the margin (no runner-up), and the
     * adjudicator rules EXACT on the pushed pronunciation id.
     *
     * <p>Templates are the spaced l1 reference decodes (the g2p-drafted shape
     * game content ships for ja chant lines; the spell JSONs carry no ja body
     * templates). Pinned real-machine values below; the heard strings differ
     * from the l1 fixtures by one vowel token (bu/bɯ, iie/ie) — the l1 bench
     * decoded with a torchaudio fbank, the production chain with KaldiFbank;
     * the production decode is deterministic and pinned here.
     */
    @Test
    void jaChantLinesAdjudicateExactOnTheirTemplates() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/zipa-ipa not found");

        List<SessionVocabulary.Entry> entries = new ArrayList<>(anemosVocabulary());
        entries.add(new SessionVocabulary.Entry("wizardreal:ventus.chant.ja.0:1",
                List.of("katze dʒi tsɯ morɯ"), List.of("風が積もる"), null, null));
        entries.add(new SessionVocabulary.Entry("wizardreal:arcanum.chant.ja.0:1",
                List.of("mamoru keda bɯriuo tsɯmɯre"), List.of("守る毛を摘むれ"), null, null));

        ZipaPhonemeRecognizer recognizer = new ZipaPhonemeRecognizer();
        recognizer.setVocabulary(new SessionVocabulary(entries));

        // ventus ja body line 風が積もる (l1: ventus__chant__ja__l2_0, nanamineural -15).
        Path kaze = wav("lab/corpus/ventus__chant__ja__l2_0__kaze_ga_tsumoru__ja_jp_nanamineural__-15.wav");
        assumeTrue(kaze != null, "lab corpus wav not present: ventus ja l2_0");
        RecognitionResult r = run(recognizer, model, kaze);
        assertEquals(Decision.EXACT, r.decision(), "kaze ga tsumoru must adjudicate EXACT");
        assertEquals("wizardreal:ventus.chant.ja.0:1", r.pronId());
        assertEquals("katzedʒiietsɯmorɯ", r.ipa().replace(" ", ""));
        assertEquals(0.8335f, r.score(), EPS);
        RecognitionDiagnostics diag = recognizer.lastDiagnostics();
        assertNotNull(diag);
        assertFalse(diag.marginRejected(), "decisive top1 must clear the margin gate");
        assertEquals(0.8335f, diag.marginTop1(), EPS);
        assertEquals(0.1042f, diag.marginTop2(), EPS);
        assertTrue(diag.marginTop1() - diag.marginTop2() >= 0.02f, "gap >= CTC margin");

        // arcanum ja body line 守る毛を摘むれ (l1: arcanum__chant__ja__l2_0, keitaneural -15).
        Path mamoru = wav("lab/corpus/arcanum__chant__ja__l2_0__mamoru_ke_wo_tsumure__ja_jp_keitaneural__-15.wav");
        assumeTrue(mamoru != null, "lab corpus wav not present: arcanum ja l2_0");
        r = run(recognizer, model, mamoru);
        assertEquals(Decision.EXACT, r.decision(), "mamoru ke wo tsumure must adjudicate EXACT");
        assertEquals("wizardreal:arcanum.chant.ja.0:1", r.pronId());
        assertEquals("mamorukedaburiuotsɯmɯre", r.ipa().replace(" ", ""));
        assertEquals(0.9949f, r.score(), EPS);
        diag = recognizer.lastDiagnostics();
        assertNotNull(diag);
        assertFalse(diag.marginRejected(), "decisive top1 must clear the margin gate");
        assertEquals(0.9949f, diag.marginTop1(), EPS);
        recognizer.stop();
    }

    /** Feed one utterance through the production pipeline and await the final. */
    private static RecognitionResult run(ZipaPhonemeRecognizer recognizer, Path model,
                                         Path wav) throws Exception {
        recognizer.start(new SpeechOptions(true, 0.65f, model.toString(), false, null));
        short[] pcm = readPcm(wav);
        AtomicReference<RecognitionResult> received = new AtomicReference<>();
        recognizer.setResultSink(received::set);
        recognizer.acceptPcm(pcm, 0, pcm.length);
        recognizer.finishUtterance();
        for (int i = 0; i < 100 && received.get() == null; i++) {
            Thread.sleep(100); // decode runs on the shared bounded pool
        }
        RecognitionResult r = received.get();
        assertNotNull(r, "recognizer must emit the decoded utterance");
        return r;
    }

    private static short[] readPcm(Path path) throws Exception {
        try (var in = AudioSystem.getAudioInputStream(path.toFile())) {
            var format = in.getFormat();
            assertEquals(16000f, format.getSampleRate(), "corpus wavs are 16 kHz");
            assertEquals(1, format.getChannels(), "corpus wavs are mono");
            assertEquals(16, format.getSampleSizeInBits(), "corpus wavs are PCM16");
            byte[] bytes = in.readAllBytes();
            short[] out = new short[bytes.length / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
            }
            return out;
        }
    }
}
