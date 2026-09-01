package com.theo.voicecast.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.model.IpaModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide shared IPA resources for {@link IpaPhonemeRecognizer}: a single
 * {@link OrtEnvironment} + {@link OrtSession} (the ~230MB q4 weights), the
 * id->phoneme vocabulary, and a bounded decode thread pool. Server-side every
 * per-player session shares this instead of loading the model N times.
 */
public final class IpaShared {
    private static volatile IpaShared INSTANCE;

    public final OrtEnvironment env;
    public final OrtSession session;
    public final String inputName;
    public final List<String> idToToken;
    private final Map<String, Integer> tokenToId;
    private final ExecutorService pool;

    private IpaShared(OrtEnvironment env, OrtSession session, String inputName,
                      List<String> idToToken, ExecutorService pool) {
        this.env = env;
        this.session = session;
        this.inputName = inputName;
        this.idToToken = idToToken;
        Map<String, Integer> t2i = new java.util.HashMap<>();
        for (int i = 0; i < idToToken.size(); i++) {
            String t = idToToken.get(i);
            if (t != null && !t.isEmpty()) t2i.putIfAbsent(t, i);
        }
        this.tokenToId = t2i;
        this.pool = pool;
    }

    /** Vocab id for an IPA token, or -1 when the token is outside the model vocabulary. */
    public int tokenId(String token) {
        Integer id = tokenToId.get(token);
        return id == null ? -1 : id;
    }

    /** Get or lazily load the shared engine from a model directory. */
    public static IpaShared getOrLoad(Path modelDir) throws Exception {
        IpaShared s = INSTANCE;
        if (s != null) return s;
        synchronized (IpaShared.class) {
            if (INSTANCE != null) return INSTANCE;
            INSTANCE = load(modelDir);
            return INSTANCE;
        }
    }

    public static IpaShared get() { return INSTANCE; }

    private static IpaShared load(Path dir) throws Exception {
        Path onnx = IpaModel.weightsFile(dir);
        Path vocab = dir.resolve(IpaModel.VOCAB_FILE);
        if (onnx == null) throw new IllegalStateException("IPA model weights not found in " + dir);
        List<String> tokens = loadVocab(vocab);

        OrtEnvironment env = OrtEnvironment.getEnvironment("voicecast");
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        so.setIntraOpNumThreads(Math.min(2, cores));
        so.setInterOpNumThreads(1);
        try { so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT); } catch (Throwable ignored) {}
        VoiceCast.LOGGER.info("Loading shared IPA ONNX model from {}", onnx.toAbsolutePath());
        OrtSession session = env.createSession(onnx.toString(), so);
        String inputName = session.getInputNames().iterator().next();

        int poolSize = Math.min(4, Math.max(1, cores));
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "VoiceCast-IpaDecode-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, tf);
        VoiceCast.LOGGER.info("Shared IPA engine ready (tokens={}, decode threads={})", tokens.size(), poolSize);
        return new IpaShared(env, session, inputName, tokens, pool);
    }

    public void submit(Runnable r) { pool.submit(r); }

    /** Run the shared session on raw waveform and CTC-greedily decode to phonemes. */
    /** Decode result: the phoneme tokens plus a mean confidence in [0,1]. */
    public record CtcResult(List<String> tokens, float confidence) {}

    /** Full decode: greedy tokens + log-softmax frames for CTC vocabulary scoring. */
    public record Decoded(CtcResult greedy, float[][] logProb) {}

    public CtcResult decodePhonemes(float[] wave) throws Exception {
        return decodeFull(wave).greedy();
    }

    public Decoded decodeFull(float[] wave) throws Exception {
        float[][] logits;
        try (OnnxTensor input = OnnxTensor.createTensor(env, new float[][]{wave})) {
            try (OrtSession.Result result = session.run(Map.of(inputName, input))) {
                @SuppressWarnings("unchecked")
                float[][][] out = (float[][][]) result.get(0).getValue();
                logits = out[0];
            }
        }
        return new Decoded(ctcGreedy(logits, idToToken), logSoftmaxFrames(logits));
    }

    static List<String> loadVocab(Path vocabFile) throws java.io.IOException {
        String json = Files.readString(vocabFile, StandardCharsets.UTF_8);
        Map<String, Integer> map = MiniJson.parseStringIntObject(json);
        int max = 0;
        for (int v : map.values()) max = Math.max(max, v);
        List<String> tokens = new ArrayList<>(max + 1);
        for (int i = 0; i <= max; i++) tokens.add("");
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getValue() >= 0 && e.getValue() < tokens.size()) tokens.set(e.getValue(), e.getKey());
        }
        return tokens;
    }

    /** Model control tokens that must never be shown as recognized output. */
    private static final java.util.Set<String> SPECIAL =
            java.util.Set.of("<s>", "</s>", "<pad>", "<unk>", "|", "[pad]", "[unk]", "");

    static CtcResult ctcGreedy(float[][] frames, List<String> idToToken) {
        List<String> out = new ArrayList<>();
        double confSum = 0;
        int confFrames = 0;
        int prev = -1;
        int blank = 0;
        for (float[] frame : frames) {
            // Softmax over the frame's logits so we get a real probability.
            float maxLogit = Float.NEGATIVE_INFINITY;
            int vocab = Math.min(frame.length, idToToken.size());
            for (int c = 0; c < vocab; c++) maxLogit = Math.max(maxLogit, frame[c]);
            float[] prob = new float[vocab];
            float denom = 0;
            for (int c = 0; c < vocab; c++) {
                prob[c] = (float) Math.exp(frame[c] - maxLogit);
                denom += prob[c];
            }
            int best = blank;
            float bestProb = 0f;
            for (int c = 0; c < vocab; c++) {
                prob[c] /= denom;
                if (prob[c] > bestProb) {
                    bestProb = prob[c];
                    best = c;
                }
            }
            if (best != blank && best != prev) {
                String tok = idToToken.get(best);
                if (tok != null && !SPECIAL.contains(tok)) {
                    out.add(tok);
                    confSum += bestProb;
                    confFrames++;
                }
            }
            prev = best;
        }
        float confidence = confFrames > 0 ? (float) (confSum / confFrames) : 0f;
        return new CtcResult(out, confidence);
    }

    // ------------------------------------------------------------- CTC scoring

    /** Numerically stable per-frame log-softmax of raw model logits. */
    static float[][] logSoftmaxFrames(float[][] logits) {
        float[][] out = new float[logits.length][];
        for (int t = 0; t < logits.length; t++) {
            float[] frame = logits[t];
            float max = Float.NEGATIVE_INFINITY;
            for (float v : frame) if (v > max) max = v;
            float[] lp = new float[frame.length];
            double denom = 0;
            for (int c = 0; c < frame.length; c++) {
                double e = Math.exp(frame[c] - max);
                lp[c] = (float) e;
                denom += e;
            }
            double logDenom = Math.log(denom);
            for (int c = 0; c < frame.length; c++) lp[c] = (float) (Math.log(lp[c]) - logDenom);
            out[t] = lp;
        }
        return out;
    }

    /** Log-probability of the all-blank path (the CTC "nothing said" hypothesis). */
    public static double nullLogProb(float[][] logProb) {
        double sum = 0;
        for (float[] frame : logProb) sum += frame[0]; // vocab index 0 = blank/<pad>
        return sum;
    }

    /**
     * Exact CTC forward log-probability of an IPA target token sequence given
     * per-frame log-softmax probabilities. This is the principled replacement
     * for post-hoc greedy correction: instead of chaining per-frame argmax
     * tokens, it sums <em>all</em> frame alignments that produce the target, so
     * systematic greedy errors (dropped weak consonants, vowel shifts) no
     * longer break matching as long as the acoustic evidence supports the
     * template overall.
     *
     * <p>Standard extended-target automaton: states 0..2L are
     * [blank, y1, blank, y2, ..., yL, blank]; state s may advance from s, s-1
     * and (when the labels differ) s-2, i.e. repeated tokens require a blank
     * between them.
     */
    public static double targetLogProb(float[][] logProb, int[] target) {
        int T = logProb.length;
        int L = target.length;
        if (L == 0) return nullLogProb(logProb);
        final int blank = 0;
        final double NEG = Double.NEGATIVE_INFINITY;
        double[] prev = new double[2 * L + 1];
        double[] cur = new double[2 * L + 1];
        prev[0] = logProb[0][blank];
        prev[1] = logProb[0][target[0]];
        for (int s = 2; s <= 2 * L; s++) prev[s] = NEG;
        for (int t = 1; t < T; t++) {
            float[] lp = logProb[t];
            for (int s = 0; s <= 2 * L; s++) {
                int label = (s % 2 == 1) ? target[s / 2] : blank;
                double best = prev[s];
                if (s >= 1 && prev[s - 1] > best) best = prev[s - 1];
                // skip s-2 only when its label differs from this state's label
                if (s >= 2) {
                    int prevLabel = (s % 2 == 1) ? target[s / 2 - 1] : blank;
                    if (prevLabel != label && prev[s - 2] > best) best = prev[s - 2];
                }
                cur[s] = (best == NEG) ? NEG : best + lp[label];
            }
            double[] tmp = prev; prev = cur; cur = tmp;
        }
        return logSumExp2(prev[2 * L - 1], prev[2 * L]);
    }

    private static double logSumExp2(double a, double b) {
        if (a == Double.NEGATIVE_INFINITY) return b;
        if (b == Double.NEGATIVE_INFINITY) return a;
        return a > b ? a + Math.log1p(Math.exp(b - a)) : b + Math.log1p(Math.exp(a - b));
    }

    public static void shutdown() {
        IpaShared s = INSTANCE;
        if (s == null) return;
        try { s.pool.shutdownNow(); } catch (Throwable ignored) {}
        try { s.session.close(); } catch (Throwable ignored) {}
        INSTANCE = null;
    }
}
