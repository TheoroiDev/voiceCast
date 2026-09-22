package com.theo.voicecast.engine;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.theo.voicecast.api.SessionVocabulary;
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
 * <p>The ONNX session is heavyweight (~1.5-2.8 GB native RAM), so recognizers
 * are cached per (model dir, hotword set) across all sessions — but BOUNDED:
 * cast-mode switches mint one session per hotword set (the ChantManager
 * narrows the trigger roster per mode), an unbounded map would accumulate a
 * native session per mode x player x language routing. The cache is an LRU
 * over {@link #SHARED_CACHE_LIMIT} hotword sets (evicted entries are closed
 * through sherpa's {@code release()}, deferred while a decode is in flight
 * on them); the hotword-free instance (the G-QWEN3 empty-transcript fallback)
 * is PINNED and never evicted. Same-set reuse never reloads. The emitted
 * result carries the adjudicated Decision (text line only — no phoneme/CTC
 * evidence on this engine).
 *
 * <p>Non-final by design: the two-pass fallback flow is scripted in tests via
 * a {@code decode} override; addons may likewise adapt decoding.
 */
public class SherpaQwen3Recognizer extends AbstractBufferedRecognizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCast");

    /** Hotword cap per session-language subset (G-QWEN3 condition ①). */
    static final int MAX_HOTWORDS = 100;

    /**
     * LRU bound on resident hotword-set recognizers (R1 H3 fix): every cast
     * mode that narrows the trigger roster mints a distinct hotword set, so
     * the bound caps the native footprint at {@code SHARED_CACHE_LIMIT}
     * sessions (~1.5-2.8 GB each) plus the pinned hotword-free one.
     */
    static final int SHARED_CACHE_LIMIT = 2;

    /** One shared native recognizer + the in-flight decode count that gates a
     *  safe {@code release()} on LRU eviction (never released under a running
     *  decode). */
    static final class SharedRecognizer {
        final OfflineRecognizer recognizer;
        final boolean hotwordFree;
        final java.util.concurrent.atomic.AtomicInteger busy =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean pendingRelease;

        SharedRecognizer(OfflineRecognizer recognizer, boolean hotwordFree) {
            this.recognizer = recognizer;
            this.hotwordFree = hotwordFree;
        }
    }

    /** "(modelDir)#(hotwords csv)" -> shared recognizer entry. Mutation
     *  (create/evict/release-on-idle) happens under {@link #SHARED_LOCK};
     *  reads are lock-free. */
    private static final Map<String, SharedRecognizer> SHARED = new ConcurrentHashMap<>();
    /** Create/evict gate + LRU order of SHARED keys (eldest first). */
    private static final Object SHARED_LOCK = new Object();
    private static final LinkedHashSet<String> SHARED_LRU = new LinkedHashSet<>();
    /** Native loads so far (test observability: same-set reuse = no reload). */
    private static final java.util.concurrent.atomic.AtomicLong SHARED_LOADS =
            new java.util.concurrent.atomic.AtomicLong();

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
     * projected onto the session's language buckets by the session router.
     * Dedup in encounter order, hard cap {@link #MAX_HOTWORDS}.
     */
    static List<String> extractHotwords(SessionVocabulary vocabulary) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (vocabulary != null) {
            for (SessionVocabulary.Entry p : vocabulary.entries()) {
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

    /**
     * Acquire the shared recognizer for this (model, hotword set): a resident
     * instance is reused without reloading (LRU touch), a new one is loaded
     * + cached otherwise, and the least-recently used hotword sets beyond
     * {@link #SHARED_CACHE_LIMIT} are evicted — closed through sherpa's
     * {@code release()}, deferred while a decode is still in flight on them.
     * The hotword-free set is PINNED: it does not count toward the bound and
     * is never evicted (the G-QWEN3 fallback must not pay a native reload).
     * Callers MUST {@link #releaseShared} the returned entry.
     */
    SharedRecognizer acquire(List<String> hotwords) {
        Path modelDir = spec.modelDir();
        String csv = hotwordsCsv(hotwords);
        String key = modelDir.toAbsolutePath().normalize() + "#" + csv;
        synchronized (SHARED_LOCK) {
            SharedRecognizer entry = SHARED.get(key);
            if (entry == null) {
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
                    entry = new SharedRecognizer(new OfflineRecognizer(cfg), csv.isEmpty());
                    SHARED_LOADS.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load Qwen3-ASR model '" + key + "'", e);
                }
                SHARED.put(key, entry);
            }
            // LRU touch + eviction of the least recently used hotword sets.
            SHARED_LRU.remove(key);
            SHARED_LRU.add(key);
            evictOverLimitLocked();
            entry.busy.incrementAndGet();
            return entry;
        }
    }

    /** Evict the least recently used hotword-SET keys beyond the bound
     *  (SHARED_LOCK held). Only hotword sets count toward the bound — the
     *  pinned hotword-free fallback is skipped (and never evicted). */
    private static void evictOverLimitLocked() {
        long sets = 0;
        for (String k : SHARED_LRU) {
            SharedRecognizer e = SHARED.get(k);
            if (e != null && !e.hotwordFree) sets++;
        }
        while (sets > SHARED_CACHE_LIMIT) {
            String eldest = null;
            for (String k : SHARED_LRU) {
                SharedRecognizer e = SHARED.get(k);
                if (e != null && !e.hotwordFree) {
                    eldest = k;
                    break;
                }
            }
            if (eldest == null) break; // only pinned entries resident
            SHARED_LRU.remove(eldest);
            SharedRecognizer evicted = SHARED.remove(eldest);
            sets--;
            if (evicted != null) {
                synchronized (evicted) {
                    evicted.pendingRelease = true;
                    if (evicted.busy.get() == 0) releaseNative(evicted);
                }
            }
        }
    }

    /** Release an entry acquired via {@link #acquire} (decode pass finished).
     *  A pending eviction fires the native close once the last decode is out.
     *  The decrement→pendingRelease check→release sequence and the eviction
     *  path's set→busy check→release are both atomic per entry (entry
     *  monitor): interleaved, exactly one side observes the closing
     *  condition — without this, a concurrent eviction and release can
     *  both pass their checks and double-{@code release()} the native. */
    static void releaseShared(SharedRecognizer entry) {
        synchronized (entry) {
            if (entry.busy.decrementAndGet() == 0 && entry.pendingRelease) {
                releaseNative(entry);
            }
        }
    }

    private static void releaseNative(SharedRecognizer entry) {
        try {
            entry.recognizer.release();
        } catch (Throwable t) {
            LOGGER.warn("Failed to release shared Qwen3-ASR recognizer", t);
        }
    }

    // -------------------------------------------------- test observability

    /** Test hook: resident native recognizers (hotword sets + pinned fallback). */
    static int sharedCacheSizeForTest() {
        synchronized (SHARED_LOCK) {
            return SHARED.size();
        }
    }

    /** Test hook: native loads so far — same-set reuse must not move it. */
    static long sharedLoadsForTest() {
        return SHARED_LOADS.get();
    }

    /** Test hook: whether the model's hotword-free (pinned) instance is resident. */
    static boolean hotwordFreeResidentForTest(Path modelDir) {
        synchronized (SHARED_LOCK) {
            return SHARED.containsKey(modelDir.toAbsolutePath().normalize() + "#");
        }
    }

    /** Test hook: drop the whole cache (sequential tests only — releases natively). */
    static void clearSharedForTest() {
        synchronized (SHARED_LOCK) {
            for (SharedRecognizer e : SHARED.values()) {
                synchronized (e) {
                    e.pendingRelease = true;
                    if (e.busy.get() == 0) releaseNative(e);
                }
            }
            SHARED.clear();
            SHARED_LRU.clear();
        }
    }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        hotwords = extractHotwords(vocabulary);
        releaseShared(acquire(hotwords)); // fail fast if model is broken (instance stays cached)
        super.start(options);
        LOGGER.info("sherpa Qwen3-ASR recognizer ready (engine={}, hotwords={})",
                spec.engineId(), hotwords.size());
    }

    @Override
    public synchronized void setVocabulary(SessionVocabulary v) {
        super.setVocabulary(v);
        // Hotwords are baked into the native recognizer at construction; a live
        // vocabulary change rebuilds it only when already running (rare — reload).
        if (active && !spec.engineId().isEmpty()) {
            try {
                stop();                 // base stop clears the vocabulary list
                super.setVocabulary(v); // re-seed before start re-extracts hotwords
                start(new SpeechOptions(true, 0.65f, spec.modelDir().toString(), true,
                        options == null ? null : options.calibration()));
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
            String text;
            SharedRecognizer hot = acquire(hotwords);
            try {
                text = decode(hot.recognizer, floats);
            } finally {
                releaseShared(hot);
            }
            if ((text == null || text.isBlank()) && !hotwords.isEmpty()) {
                // G-QWEN3 condition ②: one hotword-free re-decode of the same
                // utterance before giving up (L2b: recovers 13/13 lab empties).
                LOGGER.debug("[QWEN3] empty transcript with hotwords, re-decoding without");
                SharedRecognizer free = acquire(List.of());
                try {
                    text = decode(free.recognizer, floats);
                } finally {
                    releaseShared(free);
                }
            }
            if (text == null || text.isBlank()) return;
            // Semantic contract v2: the text line is adjudicated against the
            // routed vocabulary (the "qwen3 vocab gate" — a transcript that
            // hits no entry is REJECTED, never cast on).
            emitAdjudicated(text.trim().toLowerCase(Locale.ROOT), List.of(), startMs,
                    null, null, String.join(",", spec.languages()));
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
