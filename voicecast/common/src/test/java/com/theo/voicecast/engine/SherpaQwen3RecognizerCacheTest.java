package com.theo.voicecast.engine;

import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.engine.EngineSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SHARED native recognizer cache is an LRU over {@link
 * SherpaQwen3Recognizer#SHARED_CACHE_LIMIT} hotword sets with the hotword-free
 * instance PINNED (R1 H3 fix): cast-mode switches mint one native session per
 * distinct trigger roster, so the bound caps the native footprint (previously
 * the cache grew unboundedly, one ~1.5-2.8 GB session per mode x player x
 * language routing), same-set reuse never reloads, and the G-QWEN3
 * empty-transcript fallback never pays a native reload.
 *
 * <p>Runs on the real workspace model ({@code resources/models/qwen3-asr-0.6b-int8})
 * — skipped on checkouts without it. Modes are exercised through start (the
 * fail-fast acquire) and the live {@code setVocabulary} rebuild, exactly the
 * production ChantManager path, without any audio.
 */
class SherpaQwen3RecognizerCacheTest {

    private static Path modelDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve("resources/models/qwen3-asr-0.6b-int8");
            if (Files.isRegularFile(cand.resolve("encoder.int8.onnx"))) return cand;
        }
        return null;
    }

    /** One trigger entry per alias (distinct ids, no chant markers). */
    private static SessionVocabulary mode(String... aliases) {
        List<SessionVocabulary.Entry> entries = new java.util.ArrayList<>();
        for (String alias : aliases) {
            entries.add(new SessionVocabulary.Entry("e2e-" + alias, List.of(), List.of(alias), null, null));
        }
        return new SessionVocabulary(entries);
    }

    private SherpaQwen3Recognizer recognizer;

    @AfterEach
    void tearDown() {
        if (recognizer != null) recognizer.stop();
        SherpaQwen3Recognizer.clearSharedForTest();
    }

    @Test
    void castModeSwitchesStayWithinLruBoundReuseSameSetAndPinTheFallback() throws Exception {
        Path model = modelDir();
        assumeTrue(model != null, "workspace resources/models/qwen3-asr-0.6b-int8 not found");
        SherpaQwen3Recognizer.clearSharedForTest();
        EngineSpec spec = new EngineSpec("sherpa-qwen3", "qwen3-asr-0.6b-int8", model,
                List.of(), java.util.Map.of());
        recognizer = new SherpaQwen3Recognizer(spec);
        SpeechOptions options = new SpeechOptions(true, 0.65f, model.toString(), false, null);

        // Mode A: first hotword set loads natively.
        recognizer.setVocabulary(mode("alpha", "bravo"));
        recognizer.start(options);
        assertEquals(1, SherpaQwen3Recognizer.sharedCacheSizeForTest(), "mode A resident");
        long loads = SherpaQwen3Recognizer.sharedLoadsForTest();
        assertEquals(1, loads, "exactly one native load for mode A");

        // Mode B (the ChantManager chant-narrow switch): live setVocabulary
        // rebuild mints a second set.
        recognizer.setVocabulary(mode("charlie", "delta"));
        assertEquals(2, SherpaQwen3Recognizer.sharedCacheSizeForTest(), "mode B resident");
        assertEquals(2, SherpaQwen3Recognizer.sharedLoadsForTest(), "one new native load for mode B");

        // Mint the pinned hotword-free instance (production: the G-QWEN3
        // empty-transcript fallback decode).
        SherpaQwen3Recognizer.releaseShared(recognizer.acquire(List.of()));
        assertEquals(3, SherpaQwen3Recognizer.sharedCacheSizeForTest(), "fallback pinned resident");
        long afterFallback = SherpaQwen3Recognizer.sharedLoadsForTest();

        // Modes C/D/E: the LRU bound evicts the least recently used hotword
        // SETS, never the pinned fallback — cache stays bounded at
        // SHARED_CACHE_LIMIT sets + fallback.
        recognizer.setVocabulary(mode("echo", "foxtrot")); // evicts A
        recognizer.setVocabulary(mode("golf", "hotel"));   // evicts B
        recognizer.setVocabulary(mode("india", "juliet")); // evicts C (fallback skipped)
        assertEquals(3, SherpaQwen3Recognizer.sharedCacheSizeForTest(),
                "bounded: " + SherpaQwen3Recognizer.SHARED_CACHE_LIMIT + " sets + pinned fallback");
        assertTrue(SherpaQwen3Recognizer.hotwordFreeResidentForTest(model),
                "the pinned hotword-free fallback must survive eviction");
        assertEquals(afterFallback + 3, SherpaQwen3Recognizer.sharedLoadsForTest(),
                "exactly one native load per NEW hotword set — the pinned fallback "
                        + "must not have been evicted and reloaded (+4 would mean it was)");

        // Same-set reuse: switching to the most recently used resident set
        // must not reload it.
        recognizer.setVocabulary(mode("golf", "hotel")); // golf/hotel is resident (MRU side)
        assertEquals(afterFallback + 3, SherpaQwen3Recognizer.sharedLoadsForTest(),
                "same-set switch must reuse the resident native instance (zero reload)");
        assertEquals(3, SherpaQwen3Recognizer.sharedCacheSizeForTest(), "still bounded");
    }
}
