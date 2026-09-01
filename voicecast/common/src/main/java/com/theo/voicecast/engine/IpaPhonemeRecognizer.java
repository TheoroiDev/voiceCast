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
        VoiceCast.LOGGER.info("IPA phoneme recognizer ready (shared tokens={})",
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
            VoiceCast.LOGGER.warn("IPA decode requested but shared engine is not loaded");
            return;
        }
        shared.submit(() -> {
            try {
                runDecode(shared, audio, startMs);
            } catch (Throwable t) {
                VoiceCast.LOGGER.warn("IPA decode failed", t);
            } finally {
                synchronized (this) { decoding = false; }
            }
        });
    }

    private void runDecode(IpaShared shared, short[] audio, long startMs) throws Exception {
        long minSamples = (long) (16_000 * 0.25); // ignore <250ms of audio
        if (audio.length < minSamples) {
            VoiceCast.LOGGER.debug("[IPA] utterance too short ({} samples), skipping", audio.length);
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
            VoiceCast.LOGGER.info("[IPA DEBUG] raw tokens ({}): {}", tokens.size(), tokens);
            for (int i = 0; i < tokens.size(); i++) {
                String tok = tokens.get(i);
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < tok.length(); j++) {
                    sb.append(String.format(java.util.Locale.ROOT, "U+%04X ", (int) tok.charAt(j)));
                }
                VoiceCast.LOGGER.info("[IPA DEBUG]   [{}] '{}' = {}", i, tok, sb);
            }
        }
        Map<String, Float> scores = scoreVocabulary(shared, decoded.logProb());
        if (tokens.isEmpty()) {
            VoiceCast.LOGGER.debug("[IPA] decoded no phonemes in {} ms", dt);
            if (!scores.isEmpty()) emit("", tokens, decoded.greedy().confidence(), startMs, scores);
            return;
        }
        String text = String.join(" ", tokens);
        float confidence = decoded.greedy().confidence();
        VoiceCast.LOGGER.info("[IPA] '{}' ({} phonemes, conf={}, {} ms)",
                text, tokens.size(), String.format(java.util.Locale.ROOT, "%.2f", confidence), dt);
        emit(text, tokens, confidence, startMs, scores);
    }

    /**
     * CTC forward score of every vocabulary template against this utterance,
     * softmaxed (with the "nothing said" null path as a competitor) into
     * posterior probabilities keyed by pronunciation id.
     */
    private Map<String, Float> scoreVocabulary(IpaShared shared, float[][] logProb) {
        List<Prepared> templates = ensurePrepared(shared);
        if (templates.isEmpty()) return Map.of();
        try {
            double nullLp = IpaShared.nullLogProb(logProb);
            Map<String, Double> bestLp = new LinkedHashMap<>();
            for (Prepared p : templates) {
                double best = Double.NEGATIVE_INFINITY;
                for (int[] target : p.targets()) {
                    double lp = IpaShared.targetLogProb(logProb, target);
                    if (lp > best) best = lp;
                }
                if (best != Double.NEGATIVE_INFINITY) {
                    bestLp.merge(p.id(), best, Math::max);
                }
            }
            if (bestLp.isEmpty()) return Map.of();
            double max = nullLp;
            for (double v : bestLp.values()) if (v > max) max = v;
            double denom = Math.exp(nullLp - max);
            for (double v : bestLp.values()) denom += Math.exp(v - max);
            Map<String, Float> out = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : bestLp.entrySet()) {
                out.put(e.getKey(), (float) (Math.exp(e.getValue() - max) / denom));
            }
            if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
                VoiceCast.LOGGER.info("[IPA CTC] null={} {}", String.format(java.util.Locale.ROOT, "%.3f",
                        Math.exp(nullLp - max) / denom), out);
            }
            return out;
        } catch (Throwable t) {
            VoiceCast.LOGGER.warn("IPA CTC vocabulary scoring failed", t);
            return Map.of();
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
                VoiceCast.LOGGER.info("IPA CTC scoring enabled for {} vocabulary entries", out.size());
            }
            return prepared;
        }
    }

    /** Template IPA string -> model token ids; whitespace becomes the '|' word marker. */
    private static int[] mapTemplate(IpaShared shared, String template) {
        List<Integer> ids = new ArrayList<>();
        for (String part : template.split("\\s+")) {
            if (part.isBlank()) continue;
            if (!ids.isEmpty()) {
                int sep = shared.tokenId("|");
                if (sep < 0) break; // no word marker in vocab: drop multi-part templates
                ids.add(sep);
            }
            for (String tok : IpaText.tokenize(part)) {
                int id = shared.tokenId(tok);
                if (id < 0) {
                    VoiceCast.LOGGER.debug("IPA template '{}' token '{}' not in model vocab; skipping template",
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
