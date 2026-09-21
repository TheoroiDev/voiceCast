package com.theo.voicecast.engine;

import com.theo.voicecast.api.Decision;
import com.theo.voicecast.api.RecognitionDiagnostics;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.model.ModelConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ZIPA decode parity against the research bench (engine-swap G1): 10 lab
 * corpus/probe rows decoded through the PRODUCTION pipeline
 * ({@link KaldiFbank} -> ORT -> CTC greedy) must reproduce the reference
 * token strings from {@code build/engine_swap/l1_rows.json} symbol for symbol
 * (10/10).
 *
 * <p>Also pins the CTC contract end-to-end: with a pushed vocabulary the
 * recognizer must emit non-empty {@code templateScores} ({@code ctcPresent})
 * for a real trigger utterance — the field WizardReal's ChantGate keys on.
 *
 * <p>Needs the workspace model copy ({@code resources/models/zipa-ipa}) and
 * the lab corpus; skipped on checkouts without them. Fixture generation:
 * {@code build/engine_swap/venv_zipa/Scripts/python.exe build/engine_swap/c1_gen_fixtures.py}.
 */
class ZipaDecodeE2ETest {

    private static final List<String[]> expected() throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (var in = ZipaDecodeE2ETest.class.getResourceAsStream("/c1/decode_expected.tsv")) {
            assertNotNull(in, "tracked fixture missing: /c1/decode_expected.tsv");
            for (String raw : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                if (line.isEmpty() || line.startsWith("#")) continue;
                // no trim() here: a trailing tab carries the empty expectation
                String[] cols = line.split("\t", -1);
                if (cols.length == 3) rows.add(cols);
            }
        }
        return rows;
    }

    private static Path modelDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve("resources/models/zipa-ipa");
            if (Files.isRegularFile(cand.resolve("model.int8.onnx"))) return cand;
        }
        return null;
    }

    @Test
    void decodesTenCorpusRowsSymbolForSymbol() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/zipa-ipa not found");
        ZipaShared shared = ZipaShared.getOrLoad(model);
        List<String[]> rows = expected();
        assertEquals(10, rows.size(), "fixture row count");
        int pass = 0;
        StringBuilder failures = new StringBuilder();
        for (String[] row : rows) {
            String caseName = row[0];
            Path wav = KaldiFbankTest.findWorkspaceFile(row[1]);
            String want = row[2];
            assumeTrue(wav != null, "corpus wav not present: " + row[1]);
            float[] wave = TestWav.readMono16k(wav);
            List<String> tokens = shared.decodePhonemes(wave).tokens();
            String got = String.join("", tokens);
            boolean ok = got.equals(want);
            if (ok) pass++;
            else failures.append(caseName).append(": want='").append(want).append("' got='").append(got).append("'\n");
        }
        assertEquals(10, pass, "decode parity failures:\n" + failures);
    }

    @Test
    void vocabularyPushKeepsCtcPresentContract() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/zipa-ipa not found");
        Path wav = KaldiFbankTest.findWorkspaceFile("lab/corpus/fulmen__trigger__en__fulmen__en_us_arianeural__+15.wav");
        assumeTrue(wav != null, "lab corpus not present");

        ZipaPhonemeRecognizer recognizer = new ZipaPhonemeRecognizer();
        AtomicReference<RecognitionResult> received = new AtomicReference<>();
        recognizer.setResultSink(received::set);
        // Template written in the template symbol space (clear l, ɡ etc.) — the
        // CTC target mapping resolves it into the ZIPA emission space.
        recognizer.setVocabulary(new SessionVocabulary(List.of(
                new SessionVocabulary.Entry("fulmen", List.of("ˈfʊlmɛn"), List.of("fulmen", "lightning"), null, null))));
        recognizer.start(new SpeechOptions(true, 0.65f, model.toString(), false, null));
        float[] wave = TestWav.readMono16k(wav);
        short[] pcm = new short[wave.length];
        for (int i = 0; i < wave.length; i++) pcm[i] = (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(wave[i] * 32768.0f)));
        recognizer.acceptPcm(pcm, 0, pcm.length);
        recognizer.finishUtterance();
        for (int i = 0; i < 100 && received.get() == null; i++) {
            Thread.sleep(100); // decode runs on the shared bounded pool
        }
        recognizer.stop();
        RecognitionResult r = received.get();
        assertNotNull(r, "recognizer must emit the decoded utterance");
        RecognitionDiagnostics diag = r.decision() == null ? null : recognizer.lastDiagnostics();
        assertNotNull(diag, "diagnostics accessor must carry the CTC line (the old ctcPresent)");
        assertTrue(diag.ctcPresent(), "ctcPresent contract: CTC scoring ran with a pushed vocabulary");
        assertTrue(diag.templateScores().containsKey("fulmen"),
                "posterior keyed by pronunciation id (diagnostics), got " + diag.templateScores().keySet());
        // Semantic contract v2: the decision is adjudicated, not raw scores.
        assertEquals(Decision.EXACT, r.decision(), "fulmen template must adjudicate EXACT");
        assertEquals("fulmen", r.pronId());
    }
}
