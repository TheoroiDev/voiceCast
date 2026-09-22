package com.theo.voicecast.engine;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.engine.EngineSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R2 F-a1 regression: two sessions with the SAME hotword set share one
 * {@link SherpaQwen3Recognizer.SharedRecognizer} entry (busy &gt; 1 is a real
 * production path), and the native decode must be serialized on the entry
 * monitor. sherpa-onnx v1.13.7 has no internal locking; only greedy decoding
 * (temperature &le; 1e-6 — the builder default voicecast never overrides)
 * keeps per-decode state free of shared mutable state, so the lock is the
 * guarantee. The scripted {@code decode} override tracks the concurrency
 * peak: pre-fix two parallel decodes overlap (max 2), post-fix max 1, and
 * each session's result stays its own (no cross-pollution, no exceptions).
 *
 * <p>Runs on the real workspace model ({@code resources/models/qwen3-asr-0.6b-int8})
 * — skipped on checkouts without it (same pattern as the cache test).
 */
class SherpaQwen3RecognizerConcurrencyTest {

    private static Path modelDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve("resources/models/qwen3-asr-0.6b-int8");
            if (Files.isRegularFile(cand.resolve("encoder.int8.onnx"))) return cand;
        }
        return null;
    }

    /** Concurrency instrumentation of the scripted decode. */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();

    private static final class Scripted extends SherpaQwen3Recognizer {
        final String tag;
        final Queue<RecognitionResult> results = new ConcurrentLinkedQueue<>();

        Scripted(EngineSpec spec, String tag) {
            super(spec);
            this.tag = tag;
            setResultSink(results::add);
        }

        @Override String decode(OfflineRecognizer recognizer, float[] floats) {
            int n = IN_FLIGHT.incrementAndGet();
            MAX_IN_FLIGHT.accumulateAndGet(n, Math::max);
            try {
                Thread.sleep(200); // widen the race window — pre-fix these overlap
                return "out-" + tag;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    private static SessionVocabulary vocabOf(String... aliases) {
        List<SessionVocabulary.Entry> entries = new java.util.ArrayList<>();
        for (String alias : aliases) {
            entries.add(new SessionVocabulary.Entry("e2e-" + alias, List.of(), List.of(alias), null, null));
        }
        return new SessionVocabulary(entries);
    }

    @AfterEach
    void tearDown() {
        SherpaQwen3Recognizer.clearSharedForTest();
    }

    @Test
    void concurrentFinishUtterancesOnOneSharedEntrySerializeAndStayIsolated() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/qwen3-asr-0.6b-int8 not found");
        SherpaQwen3Recognizer.clearSharedForTest();
        IN_FLIGHT.set(0);
        MAX_IN_FLIGHT.set(0);

        EngineSpec spec = new EngineSpec("sherpa-qwen3", "qwen3-asr-0.6b-int8", model,
                List.of(), Map.of());
        Scripted a = new Scripted(spec, "a");
        Scripted b = new Scripted(spec, "b");
        SessionVocabulary vocab = vocabOf("alpha", "bravo");
        SpeechOptions options = new SpeechOptions(true, 0.65f, model.toString(), false, null);
        a.setVocabulary(vocab);
        a.start(options);
        b.setVocabulary(vocab); // same hotword set → same shared native entry
        b.start(options);

        short[] pcm = new short[16_000]; // 1 s utterance (> the 250 ms noise floor)
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        try {
            for (Scripted r : new Scripted[]{a, b}) {
                pool.submit(() -> {
                    try {
                        r.acceptPcm(pcm, 0, pcm.length);
                        r.finishUtterance();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(180, TimeUnit.SECONDS), "both decodes must complete");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, MAX_IN_FLIGHT.get(),
                "native decode must be serialized per shared entry (R2 F-a1) — a max of 2 means the pre-fix race");
        assertEquals(1, a.results.size(), "session A got exactly its own result");
        assertEquals(1, b.results.size(), "session B got exactly its own result");
        assertEquals("out-a", a.results.peek().utteranceText(), "no cross-pollution into A");
        assertEquals("out-b", b.results.peek().utteranceText(), "no cross-pollution into B");
        assertEquals(1, SherpaQwen3Recognizer.sharedCacheSizeForTest(),
                "both sessions used the same (modelDir, hotword csv) entry");
    }
}
