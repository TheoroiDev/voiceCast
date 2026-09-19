package com.theo.voicecast.engine;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.IpaText;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IPA phoneme recognizer backed by wav2vec2-lv-60-espeak-cv-ft (ONNX Runtime).
 *
 * <p>PCM is buffered for one utterance and, on {@link #finishUtterance()}
 * (release or silence endpoint), decoded on the shared engine's bounded thread
 * pool via {@link IpaShared}. Client-side it loads its own shared model;
 * server-side all sessions share one {@link IpaShared} (single OrtSession +
 * pool). Output is a sequence of Unicode IPA phoneme tokens.
 *
 * <p>When an IPA vocabulary is pushed (spells/chant lines), each decode also
 * runs an exact CTC forward pass per vocabulary template and emits
 * posterior probabilities ({@code templateScores} on the result): greedy
 * per-frame argmax systematically drops weak consonants or shifts vowels, but
 * the forward pass sums all alignments and stays robust to those errors.
 * Scoring happens inside the decode worker, so the numbers always belong to
 * the emitted utterance (no cross-thread logits races between sessions).
 */
public final class IpaPhonemeRecognizer extends AbstractBufferedRecognizer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoiceCast");

    /**
     * CTC top1-top2 gap rejection margin (S6-MATCHER WO D1/D3, issue #29 P6
     * port — IN-PRODUCTION as of voicecast 0.4.x; the lab copy at
     * {@code ipa/match/Resolver.java} was AHEAD and is now semantically
     * aligned): a CTC posterior set only counts as an acceptance candidate
     * when the gap between the best score and the best NON-top1 template
     * score is at least this value. Utterances with a closer runner-up are
     * ambiguous, so {@link #scoreVocabulary} zeroes every emitted score
     * (never a partial suppression — the runner-up must not inherit the
     * win): downstream consumers see "CTC present, nothing above threshold",
     * which keeps {@code ctcPresent}-gated fallback suppression intact in
     * WizardReal's ChantGate and lets the utterance fall through like any
     * other CTC miss. WizardReal's {@code FORWARD_MATCH_THRESHOLD} (0.10)
     * still applies on top — the gap rule is an additional condition, not a
     * replacement. Package-visible non-final so the boundary test can pin the
     * exact float difference (no public API surface — see D4: no signature
     * change, no version bump). Calibration: m1_retest_v3_report §1.1
     * (build/accent_calibration) — margin 0.02 -> FPR 0.3% / recall 42.8% /
     * misfire 3.5%, the FRR minimum under FPR <= 2%.
     */
    static float CTC_MARGIN = 0.02f;


    // Growable primitive buffer: a long chant can be hundreds of thousands of
    // samples, so boxing into ArrayList<Short> would churn megabytes of garbage
    // per utterance.
    private short[] buffer = new short[32_000];
    private int bufferLen;
    private long utteranceStart;
    private volatile boolean decoding;

    /** Vocabulary templates mapped to model token ids, ready for CTC scoring. */
    private record Prepared(String id, List<int[]> targets) {}
    private volatile List<Prepared> prepared = List.of();

    public IpaPhonemeRecognizer() {}

    @Override public String id() { return "ipa-phonemes"; }
    @Override public String displayName() { return "wav2vec2 espeak IPA phonemes (offline)"; }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        // Load the shared engine from the configured model directory (no-op if
        // the server already loaded it).
        IpaShared.getOrLoad(java.nio.file.Path.of(options.modelPath()));
        prepared = null; // rebuild against the now-available vocabulary mapping
        super.start(options);
        LOGGER.info("IPA phoneme recognizer ready (shared tokens={})",
                IpaShared.get().idToToken.size());
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
        IpaShared shared = IpaShared.get();
        if (shared == null) {
            synchronized (this) { decoding = false; }
            LOGGER.warn("IPA decode requested but shared engine is not loaded");
            return;
        }
        shared.submit(() -> {
            try {
                runDecode(shared, audio, startMs);
            } catch (Throwable t) {
                LOGGER.warn("IPA decode failed", t);
            } finally {
                synchronized (this) { decoding = false; }
            }
        });
    }

    private void runDecode(IpaShared shared, short[] audio, long startMs) throws Exception {
        long minSamples = (long) (16_000 * 0.25); // ignore <250ms of audio
        if (audio.length < minSamples) {
            LOGGER.debug("[IPA] utterance too short ({} samples), skipping", audio.length);
            return;
        }
        float[] wave = new float[audio.length];
        for (int i = 0; i < audio.length; i++) wave[i] = audio[i] / 32768.0f;

        long t0 = System.currentTimeMillis();
        IpaShared.Decoded decoded = shared.decodeFull(wave);
        long dt = System.currentTimeMillis() - t0;
        List<String> tokens = decoded.greedy().tokens();
        if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
            // Token-level debug (code points included) for diagnosing phoneme
            // mismatches such as dark-L ɫ vs clear-l — see workspace-root docs/IPA识别问题.md.
            LOGGER.info("[IPA DEBUG] raw tokens ({}): {}", tokens.size(), tokens);
            for (int i = 0; i < tokens.size(); i++) {
                String tok = tokens.get(i);
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < tok.length(); j++) {
                    sb.append(String.format(java.util.Locale.ROOT, "U+%04X ", (int) tok.charAt(j)));
                }
                LOGGER.info("[IPA DEBUG]   [{}] '{}' = {}", i, tok, sb);
            }
        }
        Map<String, Float> scores = scoreVocabulary(shared, decoded.logProb());
        if (tokens.isEmpty()) {
            LOGGER.debug("[IPA] decoded no phonemes in {} ms", dt);
            if (!scores.isEmpty()) emit("", tokens, decoded.greedy().confidence(), startMs, scores);
            return;
        }
        String text = String.join(" ", tokens);
        float confidence = decoded.greedy().confidence();
        LOGGER.info("[IPA] '{}' ({} phonemes, conf={}, {} ms)",
                text, tokens.size(), String.format(java.util.Locale.ROOT, "%.2f", confidence), dt);
        emit(text, tokens, confidence, startMs, scores);
    }

    /**
     * CTC forward score of every vocabulary template against this utterance,
     * softmaxed (with the "nothing said" null path as a competitor) into
     * posterior probabilities keyed by pronunciation id.
     *
     * <p>Token-length calibration (R3, docs/ipa/ipa-backtest.md 2026-09-13/15):
     * each template's score is its forward log-prob DIVIDED by the mapped
     * target token count L (the quantity the automaton actually scored); the
     * null competitor stays a raw frame-sum (it emits zero tokens, per-token
     * division is undefined). Without this, frame-sum scores saturate: on the
     * production-scale vocabulary, non-spell speech false-accepted at 82% and
     * the threshold had no usable operating point. With it (threshold 0.10):
     * 2.6% false-accept, positive recall +1.5pp.
     */
    private Map<String, Float> scoreVocabulary(IpaShared shared, float[][] logProb) {
        List<Prepared> templates = ensurePrepared(shared);
        if (templates.isEmpty()) return Map.of();
        try {
            double nullLp = IpaShared.nullLogProb(logProb);
            Map<String, double[]> best = new LinkedHashMap<>(); // id -> {bestLp, bestLen}
            for (Prepared p : templates) {
                double bestLp = Double.NEGATIVE_INFINITY;
                int bestLen = 0;
                for (int[] target : p.targets()) {
                    double lp = IpaShared.targetLogProb(logProb, target);
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
                LOGGER.info("[IPA CTC] null={} {}", String.format(java.util.Locale.ROOT, "%.3f",
                        Math.exp(nullLp - max) / denom), out);
            }
            return out;
        } catch (Throwable t) {
            LOGGER.warn("IPA CTC vocabulary scoring failed", t);
            return Map.of();
        }
    }

    /**
     * S6 WO D1/D3 margin gate on the final posterior map (issue #29 P6 port):
     * find the top1 template score and the top2 — the best score among the
     * templates that are NOT the top1 entry; when
     * {@code top1 - top2 < CTC_MARGIN} the win is ambiguous and EVERY score
     * is zeroed (the CTC tier then cannot fire downstream, and the
     * utterance falls through exactly like any other CTC miss). With no
     * runner-up entry the decision is unambiguous (gap = top1 - 0), matching
     * the lab rule.
     *
     * <p>Note the deliberate template-level top2 (per work order D3: top2 =
     * the best non-top1 template score): the lab calibrated on SPELL-level
     * gaps, but this recognizer only knows pronunciation ids — the spell
     * grouping lives in WizardReal, and D3 ruled the margin layer stays here
     * without touching WizardReal. Consequence: two pronunciations of the
     * same spell landing within 0.02 of each other now reject where the lab
     * spell-level rule would accept — recorded as a known behavior
     * difference in the P6 port report.
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
            posteriors.replaceAll((k, v) -> 0.0f);
        }
    }

    /** Lazily map the pushed vocabulary's IPA templates to model token ids. */
    private List<Prepared> ensurePrepared(IpaShared shared) {
        List<Prepared> p = prepared;
        if (p != null) return p;
        synchronized (this) {
            if (prepared != null) return prepared;
            List<Prepared> out = new ArrayList<>();
            for (Pronunciation pron : vocabulary) {
                List<int[]> targets = new ArrayList<>();
                for (String template : pron.ipa()) {
                    int[] ids = mapTemplate(shared, template);
                    if (ids.length > 0) targets.add(ids);
                }
                if (!targets.isEmpty()) out.add(new Prepared(pron.id(), List.copyOf(targets)));
            }
            prepared = List.copyOf(out);
            if (!out.isEmpty()) {
                LOGGER.info("IPA CTC scoring enabled for {} vocabulary entries", out.size());
            }
            return prepared;
        }
    }

    /**
     * Template IPA string -> model token ids. With a word-marker token in the
     * model vocab, whitespace maps to it; WITHOUT one (the current espeak
     * vocab.json has no "|"), multi-word templates are CONCATENATED into a
     * continuous stream instead of dropped — the CTC automaton absorbs
     * inter-word transitions via blanks (R3 前置条件①, unlocks the G2P chant
     * drafts: mappable templates 18 -> 880 on the lab bench).
     */
    static int[] mapTemplate(IpaShared shared, String template) {
        List<Integer> ids = new ArrayList<>();
        int sep = shared.tokenId("|");
        for (String part : template.split("\\s+")) {
            if (part.isBlank()) continue;
            if (!ids.isEmpty() && sep >= 0) ids.add(sep);
            for (String tok : IpaText.tokenize(part)) {
                int id = shared.tokenId(tok);
                if (id < 0) {
                    LOGGER.debug("IPA template '{}' token '{}' not in model vocab; skipping template",
                            template, tok);
                    return new int[0];
                }
                ids.add(id);
            }
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        bufferLen = 0;
        decoding = false;
        prepared = null;
    }
}
