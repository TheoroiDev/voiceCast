package com.theo.voicecast.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.model.ZipaModel;

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
 * Process-wide shared ZIPA resources for {@link ZipaPhonemeRecognizer} (the
 * {@code ipa} engine family backend as of 0.5.0): a single ORT
 * {@link OrtSession} over {@code model.int8.onnx} (zipa-small-crctc-ns-no-diacritics,
 * ~70 MB int8, loaded DIRECTLY — sherpa-onnx does not expose the frame-level
 * posteriors the CTC template layer needs, engine-swap G-ZIPA Route B), the
 * id->symbol vocabulary, and a bounded decode thread pool. Server-side every
 * per-player session shares this instead of loading the model N times.
 *
 * <p>Decode pipeline (engine-swap L1 口径, validated bit-for-bit against the
 * research bench): {@link KaldiFbank} (80-dim kaldi fbank, 25/10 ms, dither=0,
 * NO MVN) -> ORT ({@code x} float32 [1,T,80] + {@code x_lens} int64 [T]) ->
 * logits [T,V] -> CTC greedy (blank = id 0 {@code <blk>}, repeated symbols
 * collapsed). The model is no-diacritics: it never emits stress, length,
 * aspiration or combining marks (L1 measured inventory of 56 symbols).
 */
public final class ZipaShared {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoiceCast");

    private static volatile ZipaShared INSTANCE;

    public final OrtEnvironment env;
    public final OrtSession session;
    private final String inputName;
    private final String lensName;
    public final List<String> idToToken;
    private final Map<String, Integer> tokenToId;
    private final ExecutorService pool;
    /** Directory the singleton was loaded from (R2 F-B9 mismatch warning). */
    private final Path modelDir;

    private ZipaShared(OrtEnvironment env, OrtSession session, String inputName, String lensName,
                       List<String> idToToken, ExecutorService pool, Path modelDir) {
        this.env = env;
        this.session = session;
        this.inputName = inputName;
        this.lensName = lensName;
        this.idToToken = idToToken;
        Map<String, Integer> t2i = new java.util.HashMap<>();
        for (int i = 0; i < idToToken.size(); i++) {
            String t = idToToken.get(i);
            if (t != null && !t.isEmpty()) t2i.putIfAbsent(t, i);
        }
        this.tokenToId = t2i;
        this.pool = pool;
        this.modelDir = modelDir == null ? null : modelDir.toAbsolutePath().normalize();
    }

    /** Vocab id for an IPA symbol, or -1 when the symbol is outside the model vocabulary. */
    public int tokenId(String token) {
        Integer id = tokenToId.get(token);
        return id == null ? -1 : id;
    }

    /** Get or lazily load the shared engine from a model directory. */
    public static ZipaShared getOrLoad(Path modelDir) throws Exception {
        ZipaShared s = INSTANCE;
        if (s != null) {
            // R2 F-B9: the singleton ignores its request's directory — with a
            // second loose-files model that meant silently wrong weights. Not
            // reachable with today's single zipa catalog entry; make it loud
            // the day it becomes reachable.
            if (s.modelDir != null && modelDir != null
                    && !s.modelDir.equals(modelDir.toAbsolutePath().normalize())) {
                LOGGER.warn("ZIPA shared engine was loaded from {}; ignoring request for {} "
                        + "(only one loose-files IPA model is supported per process)", s.modelDir, modelDir);
            }
            return s;
        }
        synchronized (ZipaShared.class) {
            if (INSTANCE != null) return INSTANCE;
            INSTANCE = load(modelDir);
            return INSTANCE;
        }
    }

    public static ZipaShared get() { return INSTANCE; }

    private static ZipaShared load(Path dir) throws Exception {
        Path onnx = ZipaModel.weightsFile(dir);
        if (onnx == null) throw new IllegalStateException("ZIPA model weights not found in " + dir);
        List<String> tokens = loadTokens(dir.resolve(ZipaModel.TOKENS_FILE));

        OrtEnvironment env = OrtEnvironment.getEnvironment("voicecast");
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        so.setIntraOpNumThreads(Math.min(2, cores));
        so.setInterOpNumThreads(1);
        try { so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT); } catch (Throwable ignored) {}
        LOGGER.info("Loading shared ZIPA ONNX model from {}", onnx.toAbsolutePath());
        OrtSession session = env.createSession(onnx.toString(), so);
        String inputName = null;
        String lensName = null;
        for (String name : session.getInputNames()) {
            if (name.contains("len")) lensName = name;
            else inputName = name;
        }
        if (inputName == null || lensName == null) {
            throw new IllegalStateException("Unexpected ZIPA model inputs: " + session.getInputNames());
        }

        int poolSize = Math.min(4, Math.max(1, cores));
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "VoiceCast-ZipaDecode-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, tf);
        LOGGER.info("Shared ZIPA engine ready (tokens={}, decode threads={})", tokens.size(), poolSize);
        return new ZipaShared(env, session, inputName, lensName, tokens, pool, dir);
    }

    public void submit(Runnable r) { pool.submit(r); }

    /** Greedy decode result: the IPA symbols plus a mean confidence in [0,1]. */
    public record CtcResult(List<String> tokens, float confidence) {}

    /** Full decode: greedy symbols + log-softmax frames for CTC vocabulary scoring. */
    public record Decoded(CtcResult greedy, float[][] logProb) {}

    public CtcResult decodePhonemes(float[] wave) throws Exception {
        return decodeFull(wave).greedy();
    }

    public Decoded decodeFull(float[] wave) throws Exception {
        float[][] feat = KaldiFbank.compute(wave);
        if (feat.length == 0) return new Decoded(new CtcResult(List.of(), 0f), new float[0][]);
        float[][][] batch = new float[1][][];
        batch[0] = feat;
        long[] lens = new long[]{feat.length};
        float[][] logits;
        try (OnnxTensor x = OnnxTensor.createTensor(env, batch);
             OnnxTensor xLens = OnnxTensor.createTensor(env, lens)) {
            try (OrtSession.Result result = session.run(Map.of(inputName, x, lensName, xLens))) {
                @SuppressWarnings("unchecked")
                float[][][] out = (float[][][]) result.get(0).getValue();
                logits = out[0];
            }
        }
        return new Decoded(ctcGreedy(logits, idToToken), logSoftmaxFrames(logits));
    }

    /**
     * tokens.txt loader: one symbol per line as {@code <symbol> <id>} — the
     * WHOLE text before the final space is the symbol (kaldi-style two-column
     * list; symbols may in principle contain spaces).
     */
    static List<String> loadTokens(Path tokensFile) throws java.io.IOException {
        List<String> lines = Files.readAllLines(tokensFile, StandardCharsets.UTF_8);
        List<String> syms = new ArrayList<>(lines.size());
        List<Integer> ids = new ArrayList<>(lines.size());
        int max = -1;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int cut = line.lastIndexOf(' ');
            if (cut <= 0) continue; // malformed line — skip
            try {
                int id = Integer.parseInt(line.substring(cut + 1).trim());
                syms.add(line.substring(0, cut));
                ids.add(id);
                max = Math.max(max, id);
            } catch (NumberFormatException ignored) {
                // malformed id — skip line
            }
        }
        List<String> tokens = new ArrayList<>(Math.max(0, max + 1));
        for (int i = 0; i <= max; i++) tokens.add("");
        for (int i = 0; i < ids.size(); i++) tokens.set(ids.get(i), syms.get(i));
        return tokens;
    }

    /** Model control tokens (incl. the word boundary marker) never shown as output. */
    private static final java.util.Set<String> SPECIAL =
            java.util.Set.of("<blk>", "<sos/eos>", "<unk>", "\u2581", "");

    /** CTC blank symbol id (kaldi convention: id 0 = {@code <blk>}). */
    static final int BLANK_ID = 0;

    static CtcResult ctcGreedy(float[][] frames, List<String> idToToken) {
        List<String> out = new ArrayList<>();
        double confSum = 0;
        int confFrames = 0;
        int prev = -1;
        for (float[] frame : frames) {
            // softmax over the frame's logits so we get a real probability
            float maxLogit = Float.NEGATIVE_INFINITY;
            int vocab = Math.min(frame.length, idToToken.size());
            for (int c = 0; c < vocab; c++) maxLogit = Math.max(maxLogit, frame[c]);
            float[] prob = new float[vocab];
            float denom = 0;
            for (int c = 0; c < vocab; c++) {
                prob[c] = (float) Math.exp(frame[c] - maxLogit);
                denom += prob[c];
            }
            int best = BLANK_ID;
            float bestProb = 0f;
            for (int c = 0; c < vocab; c++) {
                prob[c] /= denom;
                if (prob[c] > bestProb) {
                    bestProb = prob[c];
                    best = c;
                }
            }
            if (best != BLANK_ID && best != prev) {
                String tok = best < idToToken.size() ? idToToken.get(best) : null;
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
        for (float[] frame : logProb) sum += frame[BLANK_ID];
        return sum;
    }

    /**
     * Exact CTC forward log-probability of an IPA target symbol sequence given
     * per-frame log-softmax probabilities (the template layer behind
     * {@code templateScores}). Sums ALL frame alignments that produce the
     * target, so greedy-level symbol drops/shifts do not break matching as long
     * as the acoustic evidence supports the template overall. Standard
     * extended-target automaton: states 0..2L are
     * [blank, y1, blank, y2, ..., yL, blank]; a state may repeat or advance,
     * and skipping two states is only allowed when the labels differ.
     */
    public static double targetLogProb(float[][] logProb, int[] target) {
        int T = logProb.length;
        int L = target.length;
        if (L == 0) return nullLogProb(logProb);
        final int blank = BLANK_ID;
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

    // ------------------------------------------------- template -> symbol ids

    /**
     * Marks and modifier letters the no-diacritics model NEVER emits (L1
     * measured inventory: 56 symbols, none of these) — templates are stripped
     * of them so CTC targets live in the model's own emission space. Stress
     * {@code ˈ ˌ}, length {@code ː ˑ}, aspiration/palatalization/labialization
     * {@code ʰ ʲ ʷ}, rhotacization/velarization/pharyngealization
     * {@code ˞ ˠ ˤ}, and every combining (NON_SPACING_MARK) diacritic.
     */
    private static final String STRIP = "\u02C8\u02CC\u02D0\u02D1\u02B0\u02B2\u02B7\u02DE\u02E0\u02E4";

    /**
     * Template IPA string -> model symbol ids, in the ZIPA emission space:
     * per-character symbols (the model vocabulary has no affricate units, so
     * {@code tʃ}/{@code dʒ}/{@code ts} expand into symbol pairs naturally),
     * strip/normalize per {@link #STRIP} + {@code ɡ} (U+0261) -> ASCII {@code g}
     * (the vocabulary has only the ASCII letter), whitespace concatenates
     * multi-word templates (no word-marker token; the CTC automaton absorbs
     * inter-word transitions via blanks). Templates containing a symbol outside
     * the model vocabulary map to an empty result and are skipped by the caller
     * (same semantics as the previous espeak backend).
     */
    static int[] mapTemplate(ZipaShared shared, String template) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (STRIP.indexOf(c) >= 0) continue;
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            String sym = c == '\u0261' ? "g" : String.valueOf(c); // ɡ -> g
            int id = shared.tokenId(sym);
            if (id < 0) {
                LOGGER.debug("ZIPA template '{}' symbol '{}' not in model vocab; skipping template",
                        template, sym);
                return new int[0];
            }
            ids.add(id);
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    public static void shutdown() {
        ZipaShared s = INSTANCE;
        if (s == null) return;
        try { s.pool.shutdownNow(); } catch (Throwable ignored) {}
        try { s.session.close(); } catch (Throwable ignored) {}
        INSTANCE = null;
    }
}
