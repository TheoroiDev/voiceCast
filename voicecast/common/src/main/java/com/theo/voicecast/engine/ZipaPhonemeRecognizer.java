package com.theo.voicecast.engine;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IPA phoneme recognizer backed by ZIPA (zipa-small-crctc-ns-no-diacritics
 * int8, ONNX Runtime direct) — the {@code ipa} engine family backend as of
 * 0.5.0 (engine-swap hard cut, voicecast C1). PCM is buffered for one
 * utterance and, on {@link #finishUtterance()} (release or silence endpoint),
 * decoded on the shared engine's bounded thread pool via {@link ZipaShared}.
 * Client-side it loads its own shared model; server-side all sessions share
 * one {@link ZipaShared} (single OrtSession + pool). Output is a sequence of
 * Unicode IPA phoneme symbols with the word-boundary marker stripped.
 *
 * <p>When an IPA vocabulary is pushed (spells/chant lines), each decode also
 * runs an exact CTC forward pass per vocabulary template and emits posterior
 * probabilities ({@code templateScores} on the result): greedy per-frame argmax
 * systematically drops weak consonants or shifts vowels, but the forward pass
 * sums all alignments and stays robust to those errors. Scoring happens inside
 * the decode worker, so the numbers always belong to the emitted utterance (no
 * cross-thread logits races between sessions). Template symbols resolve in the
 * ZIPA emission space ({@link ZipaShared#mapTemplate}); the CTC
 * margin/posterior semantics and the {@code templateScores}/{@code ctcPresent}
 * contract are unchanged from the previous espeak backend (WizardReal's
 * ChantGate consumes them unchanged).
 */
public final class ZipaPhonemeRecognizer extends AbstractBufferedRecognizer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoiceCast");

    /**
     * CTC top1-top2 gap rejection margin — ported unchanged from the espeak
     * backend (S6-MATCHER WO D1/D3, issue #29 P6): a CTC posterior set only
     * counts as an acceptance candidate when the gap between the best score
     * and the best NON-top1 template score is at least this value. Utterances
     * with a closer runner-up are ambiguous, so {@link #scoreVocabulary}
     * zeroes every emitted score (never a partial suppression — the runner-up
     * must not inherit the win): downstream consumers see "CTC present,
     * nothing above threshold", which keeps {@code ctcPresent}-gated fallback
     * suppression intact in WizardReal's ChantGate and lets the utterance fall
     * through like any other CTC miss. WizardReal's {@code
     * FORWARD_MATCH_THRESHOLD} (0.10) still applies on top — the gap rule is
     * an additional condition, not a replacement. Package-visible non-final so
     * the boundary test can pin the exact float difference (no public API
     * surface). Calibration: m1_retest_v3_report §1.1 (build/accent_calibration)
     * — margin 0.02 -> FPR 0.3% / recall 42.8% / misfire 3.5%.
     */
    static float CTC_MARGIN = 0.02f;

    // Growable primitive buffer: a long chant can be hundreds of thousands of
    // samples, so boxing into ArrayList<Short> would churn megabytes of garbage
    // per utterance.
    private short[] buffer = new short[32_000];
    private int bufferLen;
    private long utteranceStart;
    private volatile boolean decoding;

    /** Vocabulary templates mapped to model symbol ids, ready for CTC scoring. */
    private record Prepared(String id, List<int[]> targets) {}
    private volatile List<Prepared> prepared = List.of();

    public ZipaPhonemeRecognizer() {}

    @Override public String id() { return "zipa-ipa"; }
    @Override public String displayName() { return "ZIPA IPA phonemes (offline)"; }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        // Load the shared engine from the configured model directory (no-op if
        // the server already loaded it).
        ZipaShared.getOrLoad(java.nio.file.Path.of(options.modelPath()));
        prepared = null; // rebuild against the now-available vocabulary mapping
        super.start(options);
        LOGGER.info("ZIPA phoneme recognizer ready (shared tokens={})",
                ZipaShared.get().idToToken.size());
    }

    @Override
    protected void onVocabularyChanged() {
        prepared = null;
    }

    @Override
    protected synchronized void decode(short[] samples, int offset, int length) {
        if (utteranceStart == 0) utteranceStart = System.currentTimeMillis();
        if (bufferLen + length > buffer.length) {
            int newSize = Math.max(buffer.length * 2, bufferLen + length);
            buffer = java.util.Arrays.copyOf(buffer, newSize);
        }
        System.arraycopy(samples, offset, buffer, bufferLen, length);
        bufferLen += length;
    }

    @Override
    public void finishUtterance() {
        short[] copy;
        long start;
        synchronized (this) {
            if (decoding || bufferLen == 0) return;
            decoding = true;
            copy = java.util.Arrays.copyOf(buffer, bufferLen);
            bufferLen = 0;
            start = utteranceStart == 0 ? System.currentTimeMillis() : utteranceStart;
            utteranceStart = 0;
        }
        final short[] audio = copy;
        final long startMs = start;
        ZipaShared shared = ZipaShared.get();
        if (shared == null) {
            synchronized (this) { decoding = false; }
            LOGGER.warn("ZIPA decode requested but shared engine is not loaded");
            return;
        }
        shared.submit(() -> {
            try {
                runDecode(shared, audio, startMs);
            } catch (Throwable t) {
                LOGGER.warn("ZIPA decode failed", t);
            } finally {
                synchronized (this) { decoding = false; }
            }
        });
    }

    private void runDecode(ZipaShared shared, short[] audio, long startMs) throws Exception {
        long minSamples = (long) (16_000 * 0.25); // ignore <250ms of audio
        if (audio.length < minSamples) {
            LOGGER.debug("[ZIPA] utterance too short ({} samples), skipping", audio.length);
            return;
        }
        float[] wave = new float[audio.length];
        for (int i = 0; i < audio.length; i++) wave[i] = audio[i] / 32768.0f;

        long t0 = System.currentTimeMillis();
        ZipaShared.Decoded decoded = shared.decodeFull(wave);
        long dt = System.currentTimeMillis() - t0;
        List<String> tokens = decoded.greedy().tokens();
        Map<String, Float> scores = scoreVocabulary(shared, decoded.logProb());
        if (tokens.isEmpty()) {
            LOGGER.debug("[ZIPA] decoded no phonemes in {} ms", dt);
            if (!scores.isEmpty()) emit("", tokens, decoded.greedy().confidence(), startMs, scores);
            return;
        }
        String text = String.join(" ", tokens);
        float confidence = decoded.greedy().confidence();
        LOGGER.info("[ZIPA] '{}' ({} phonemes, conf={}, {} ms)",
                text, tokens.size(), String.format(java.util.Locale.ROOT, "%.2f", confidence), dt);
        emit(text, tokens, confidence, startMs, scores);
    }

    /**
     * CTC forward score of every vocabulary template against this utterance,
     * softmaxed (with the "nothing said" null path as a competitor) into
     * posterior probabilities keyed by pronunciation id. Token-length
     * calibration carried over from the espeak backend: each template's score
     * is its forward log-prob DIVIDED by the mapped target token count L (the
     * quantity the automaton actually scored); the null competitor stays a raw
     * frame-sum (it emits zero tokens, per-token division is undefined).
     */
    private Map<String, Float> scoreVocabulary(ZipaShared shared, float[][] logProb) {
        List<Prepared> templates = ensurePrepared(shared);
        if (templates.isEmpty()) return Map.of();
        try {
            double nullLp = ZipaShared.nullLogProb(logProb);
            Map<String, double[]> best = new LinkedHashMap<>(); // id -> {bestLp, bestLen}
            for (Prepared p : templates) {
                double bestLp = Double.NEGATIVE_INFINITY;
                int bestLen = 0;
                for (int[] target : p.targets()) {
                    double lp = ZipaShared.targetLogProb(logProb, target);
                    if (lp > bestLp) {
                        bestLp = lp;
                        bestLen = target.length;
                    }
                }
                if (bestLp != Double.NEGATIVE_INFINITY) {
                    best.merge(p.id(), new double[]{bestLp, bestLen},
                            (a, b) -> a[0] >= b[0] ? a : b);
                }
            }
            if (best.isEmpty()) return Map.of();
            double max = nullLp;
            for (double[] v : best.values()) {
                double norm = v[0] / Math.max(1, v[1]);
                if (norm > max) max = norm;
            }
            double denom = Math.exp(nullLp - max);
            for (double[] v : best.values()) denom += Math.exp(v[0] / Math.max(1, v[1]) - max);
            Map<String, Float> out = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : best.entrySet()) {
                out.put(e.getKey(), (float) (Math.exp(e.getValue()[0] / Math.max(1, e.getValue()[1]) - max) / denom));
            }
            applyCtcMargin(out);
            if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
                LOGGER.info("[ZIPA CTC] null={} {}", String.format(java.util.Locale.ROOT, "%.3f",
                        Math.exp(nullLp - max) / denom), out);
            }
            return out;
        } catch (Throwable t) {
            LOGGER.warn("ZIPA CTC vocabulary scoring failed", t);
            return Map.of();
        }
    }

    /**
     * S6 WO D1/D3 margin gate on the final posterior map (ported unchanged
     * from the espeak backend): find the top1 template score and the top2 —
     * the best score among the templates that are NOT the top1 entry; when
     * {@code top1 - top2 < CTC_MARGIN} the win is ambiguous and EVERY score is
     * zeroed (the CTC tier then cannot fire downstream, and the utterance
     * falls through exactly like any other CTC miss). With no runner-up entry
     * the decision is unambiguous (gap = top1 - 0), matching the lab rule.
     * Deliberate template-level top2: the recognizer only knows pronunciation
     * ids — the spell grouping lives in WizardReal.
     */
    static void applyCtcMargin(Map<String, Float> posteriors) {
        if (posteriors == null || posteriors.isEmpty()) return;
        String top1Id = null;
        float top1 = 0f;
        for (Map.Entry<String, Float> e : posteriors.entrySet()) {
            if (top1Id == null || e.getValue() > top1) {
                top1 = e.getValue();
                top1Id = e.getKey();
            }
        }
        float top2 = 0f; // no runner-up -> unambiguous (gap = top1 - 0)
        for (Map.Entry<String, Float> e : posteriors.entrySet()) {
            if (!e.getKey().equals(top1Id) && e.getValue() > top2) top2 = e.getValue();
        }
        if (top1 - top2 < CTC_MARGIN) {
            // Rejection observability (verbose only, never a decision input):
            // the actual gap values behind a silent all-zero templateScores map.
            if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
                LOGGER.info("[ZIPA CTC] margin reject: top1 '{}'={} top2={} gap={} < margin={}",
                        top1Id,
                        String.format(java.util.Locale.ROOT, "%.4f", top1),
                        String.format(java.util.Locale.ROOT, "%.4f", top2),
                        String.format(java.util.Locale.ROOT, "%.4f", top1 - top2),
                        String.format(java.util.Locale.ROOT, "%.4f", CTC_MARGIN));
            }
            posteriors.replaceAll((k, v) -> 0.0f);
        }
    }

    /** Lazily map the pushed vocabulary's IPA templates to model symbol ids. */
    private List<Prepared> ensurePrepared(ZipaShared shared) {
        List<Prepared> p = prepared;
        if (p != null) return p;
        synchronized (this) {
            if (prepared != null) return prepared;
            List<Prepared> out = new ArrayList<>();
            for (Pronunciation pron : vocabulary) {
                List<int[]> targets = new ArrayList<>();
                for (String template : pron.ipa()) {
                    int[] ids = ZipaShared.mapTemplate(shared, template);
                    if (ids.length > 0) targets.add(ids);
                }
                if (!targets.isEmpty()) out.add(new Prepared(pron.id(), List.copyOf(targets)));
            }
            prepared = List.copyOf(out);
            if (!out.isEmpty()) {
                LOGGER.info("ZIPA CTC scoring enabled for {} vocabulary entries", out.size());
            }
            return prepared;
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        bufferLen = 0;
        decoding = false;
        prepared = null;
    }
}
