package com.theo.voicecast.engine;

/**
 * Java port of the ZIPA inference frontend: torchaudio
 * {@code compliance.kaldi.fbank(num_mel_bins=80, frame_length=25.0, frame_shift=10.0,
 * sample_frequency=16000, dither=0.0)} — kaldi fbank, 80 mel bins, 25 ms frames
 * / 10 ms shift, povey window, preemphasis 0.97, DC removal, power spectrum,
 * log-mel with float-epsilon floor, and NO mean-variance normalization.
 *
 * <p>This is the exact recipe the ZIPA (zipa-small-crctc-ns-no-diacritics)
 * training pipeline uses ({@code zipa_ctc_inference.get_fbank}); per-utterance
 * MVN silently under-decodes the model (engine-swap L1: bench_s2p.py /
 * l1_decode_zipa.py, 12 号踩坑). The port was validated against the research
 * pipeline's saved fbank matrices ({@code _research/.../fbank/*.npy}):
 * {@code max|diff| = 0.0} over 101 clips, and against a deterministic
 * synthetic signal ({@code KaldiFbankTest}, tolerance 1e-5).
 *
 * <p>Numerics: frame operations and the mel filterbank run in double (torchaudio
 * runs them in float32; the deviation is ~1e-7 relative, compressed to ~1e-6
 * absolute by the log — well inside the 1e-5 acceptance tolerance). The FFT is
 * a 512-point radix-2 real FFT (kaldi rounds the 400-sample window up to the
 * next power of two).
 */
public final class KaldiFbank {
    private KaldiFbank() {}

    public static final int SAMPLE_RATE = 16000;
    /** Window = next power of two of the 400-sample (25 ms) frame. */
    private static final int FFT_SIZE = 512;
    private static final int FRAME = 400;          // 25 ms
    private static final int SHIFT = 160;          // 10 ms
    private static final int BINS = 80;
    /** std::numeric_limits<float>::epsilon() — torchaudio's log floor. */
    private static final double EPS_FLOAT = 1.1920928955078125e-07;

    /** Mel filterbank weights [BINS][FFT_SIZE / 2 + 1] (last column zero). */
    private static volatile double[][] melBanks;

    /**
     * Compute the fbank of 16 kHz mono waveform. Returns {@code frames x 80}
     * where {@code frames = 1 + (n - 400) / 160} (kaldi snip_edges semantics);
     * signals shorter than one frame yield a zero-length result.
     *
     * <p>Frame arithmetic (DC removal, preemphasis, windowing) runs in float32
     * — matching torchaudio's dtype — because preemphasis divides the
     * low-frequency response by ~33x and a double-precision path here drifts
     * ~1e-4 in the log-mel after cancellation (measured, synthetic fixture);
     * float32 keeps the parity inside 1e-6. The FFT and mel projection run in
     * double (their float32 deviation is ~1e-6 in the log, within tolerance).
     */
    public static float[][] compute(float[] wave) {
        if (wave == null || wave.length < FRAME) return new float[0][];
        int frames = 1 + (wave.length - FRAME) / SHIFT;
        float[] window = povey();
        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        float[] frame = new float[FFT_SIZE];
        double[][] banks = melBanks();
        float[][] out = new float[frames][BINS];
        for (int t = 0; t < frames; t++) {
            int off = t * SHIFT;
            // remove_dc_offset: subtract the frame mean (over the 400-sample
            // window, before padding)
            double sum = 0;
            for (int j = 0; j < FRAME; j++) sum += wave[off + j];
            float mean = (float) (sum / FRAME);
            // preemphasis_coefficient=0.97, computed on the DC-removed frame:
            // x[j] -= 0.97 * x[max(0, j-1)] (j = 0 shrink by the replicate rule)
            float prev = wave[off] - mean;
            float x0 = prev - 0.97f * prev;
            for (int j = 1; j < FRAME; j++) {
                float cur = wave[off + j] - mean;
                frame[j] = (cur - 0.97f * prev) * window[j];
                prev = cur;
            }
            frame[0] = x0 * window[0];
            // zero-pad 400 -> 512 (frame[FRAME..511] stay 0; im[] all zero)
            java.util.Arrays.fill(frame, FRAME, FFT_SIZE, 0.0f);
            java.util.Arrays.fill(im, 0, FFT_SIZE, 0.0);
            for (int j = 0; j < FFT_SIZE; j++) re[j] = frame[j];

            fft(re, im);

            // power spectrum -> mel energies -> log with float-eps floor
            for (int b = 0; b < BINS; b++) {
                double[] w = banks[b];
                double energy = 0;
                for (int k = 0; k <= FFT_SIZE / 2; k++) {
                    double p = re[k] * re[k] + im[k] * im[k];
                    energy += p * w[k];
                }
                out[t][b] = (float) Math.log(Math.max(energy, EPS_FLOAT));
            }
        }
        return out;
    }

    /**
     * Kaldi povey window: hann (periodic=false) ^ 0.85, replicating torchaudio's
     * float32 computation chain (float32 cos of the float32 angle, float32
     * 0.5 - 0.5*cos, float32 pow) — near the window edges the float32 cos
     * rounding (1-cos cancellation) dominates; a double-precision path drifts
     * up to ~1e-5 relative there. Float cos is emulated as
     * {@code (float) Math.cos((double) angle)} (correctly rounded).
     */
    private static float[] povey() {
        float[] w = new float[FRAME];
        final double den = FRAME - 1;
        for (int n = 0; n < FRAME; n++) {
            float angle = (float) (2.0 * Math.PI * n / den);
            float c = (float) Math.cos((double) angle);
            float hann = 0.5f - 0.5f * c;
            w[n] = (float) Math.pow((double) hann, (double) 0.85f);
        }
        return w;
    }

    /**
     * Kaldi mel banks (torchaudio {@code get_mel_banks}): low_freq=20,
     * high_freq=0 -> nyquist, vtln factor 1.0 (no warping), triangular weights
     * {@code max(0, min(up_slope, down_slope))} over {@code num_fft_bins = 256}.
     */
    private static double[][] melBanks() {
        double[][] banks = melBanks;
        if (banks == null) {
            synchronized (KaldiFbank.class) {
                if (melBanks == null) melBanks = buildMelBanks();
                banks = melBanks;
            }
        }
        return banks;
    }

    private static double[][] buildMelBanks() {
        // Replicates torchaudio's get_mel_banks in FLOAT32 arithmetic, step for
        // step (int-indexed scalar math — deterministic). torchaudio builds the
        // bank matrix in float32, and its float32 rounding (measured: up to
        // 1.4e-5 on the weights) dominates the parity error if the exact double
        // formula is used instead. float32 log is emulated as
        // (float) Math.log((double) x) — correctly rounded to float precision.
        int numBins = BINS;
        int numFftBins = FFT_SIZE / 2; // 256
        float nyquist = 0.5f * SAMPLE_RATE;
        float lowFreq = 20.0f;
        float highFreq = nyquist; // high_freq=0 -> offset from nyquist
        float fftBinWidth = (float) ((double) SAMPLE_RATE / FFT_SIZE);

        float melLow = melScale(lowFreq);
        float melHigh = melScale(highFreq);
        float melFreqDelta = (float) (((double) melHigh - (double) melLow) / (numBins + 1));

        float[] mel = new float[numFftBins];
        for (int k = 0; k < numFftBins; k++) {
            mel[k] = melScale(fftBinWidth * k);
        }
        double[][] banks = new double[numBins][numFftBins + 1]; // +1 zero col
        for (int b = 0; b < numBins; b++) {
            float leftMel = melLow + (float) b * melFreqDelta;
            float centerMel = melLow + ((float) b + 1.0f) * melFreqDelta;
            float rightMel = melLow + ((float) b + 2.0f) * melFreqDelta;
            for (int k = 0; k < numFftBins; k++) {
                float up = (mel[k] - leftMel) / (centerMel - leftMel);
                float down = (rightMel - mel[k]) / (rightMel - centerMel);
                float weight = Math.max(0.0f, Math.min(up, down));
                banks[b][k] = weight;
            }
        }
        return banks;
    }

    /** torchaudio mel_scale replicated in per-step float32: 1127 * ln(1 + f/700). */
    private static float melScale(float freq) {
        float d = freq / 700.0f;
        float s = 1.0f + d;
        float lg = (float) Math.log((double) s); // correctly-rounded float32 log
        return 1127.0f * lg;
    }

    /** In-place iterative radix-2 complex FFT (size must be a power of two). */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        // bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j |= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    int a = i + k;
                    int bIdx = a + half;
                    double tRe = re[bIdx] * curRe - im[bIdx] * curIm;
                    double tIm = re[bIdx] * curIm + im[bIdx] * curRe;
                    re[bIdx] = re[a] - tRe;
                    im[bIdx] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
