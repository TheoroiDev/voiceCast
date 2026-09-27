package com.theo.voicecast.audio;

import com.k2fsa.sherpa.onnx.DenoisedAudio;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig;
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser;
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig;
import com.theo.voicecast.model.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.theo.voicecast.model.ModelManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Optional microphone noise suppression for the recognition path (P1 of
 * docs/mic-denoise/03), backed by sherpa-onnx's streaming GTCRN denoiser —
 * 16 kHz native (no resampling), ~0.05M parameters, CPU-light.
 *
 * <p>Scope boundary: this only cleans the VoiceCast recognition path. What
 * other players hear through Simple Voice Chat is a separate capture with its
 * own noise suppression setting.
 *
 * <p>Lifecycle: one instance per capture session, created on PTT start and
 * {@link #release()}d on stop (no filter state leaks between utterances).
 * Any native/model failure permanently degrades the instance to passthrough
 * (warned once) so casting never breaks because of the denoiser.
 */
public final class NoiseSuppression {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCast");
    /** Utility models are tiny; a stalled download should not block PTT long. */
    private static final int SR = 16_000;

    private final OnlineSpeechDenoiser denoiser;
    private final Deque<Float> pending = new ArrayDeque<>();
    private volatile boolean degraded;

    NoiseSuppression(OnlineSpeechDenoiser denoiser) {
        this.denoiser = denoiser;
    }

    /**
     * Resolve the catalog's denoiser model (downloading the tiny onnx when
     * missing) and build the streaming denoiser. Returns null when the
     * catalog has no denoiser model or anything fails — callers treat null
     * as "no noise suppression".
     */
    public static NoiseSuppression create(Path gameDir, ModelConfig config) {
        ModelConfig.ModelEntry entry = config.denoiserModel();
        if (entry == null) {
            LOGGER.info("noiseSuppression enabled but the catalog has no denoiser model");
            return null;
        }
        try {
            Path dir = gameDir.resolve("config/voicecast/models").resolve(entry.id());
            ModelManager mgr = new ModelManager(gameDir, config.probe());
            for (ModelConfig.FileEntry f : entry.files()) {
                Path p = dir.resolve(f.name());
                if (!Files.isRegularFile(p) || Files.size(p) < Math.max(1, f.minBytes())) {
                    Files.createDirectories(dir);
                    mgr.downloadFile(entry.id(), f.name(), f.urls(), f.sha256(), (done, total) -> { }, f.minBytes());
                }
            }
            Path model = dir.resolve(entry.files().get(0).name());
            // GTCRN is the only wired denoiser family; sherpa's config builders
            // default debug=true, which would dump state on every run.
            OnlineSpeechDenoiser denoiser = new OnlineSpeechDenoiser(
                    OnlineSpeechDenoiserConfig.builder()
                            .setModel(OfflineSpeechDenoiserModelConfig.builder()
                                    .setGtcrn(OfflineSpeechDenoiserGtcrnModelConfig.builder()
                                            .setModel(model.toAbsolutePath().toString())
                                            .build())
                                    .setNumThreads(1)
                                    .setDebug(false)
                                    .setProvider("cpu")
                                    .build())
                            .build());
            LOGGER.info("Noise suppression ready (model {})", entry.id());
            return new NoiseSuppression(denoiser);
        } catch (Throwable t) {
            LOGGER.warn("Failed to initialize noise suppression (continuing without)", t);
            return null;
        }
    }

    /**
     * Denoise {@code pcm[offset, offset+length)} in place. The streaming
     * denoiser's output may trail the input by its internal lookahead, so
     * produced samples are carried over and emitted 1:1 with the input count.
     */
    public void process(short[] pcm, int offset, int length) {
        if (denoiser == null || degraded || length <= 0) return;
        try {
            float[] in = new float[length];
            for (int i = 0; i < length; i++) in[i] = pcm[offset + i] / 32768f;
            DenoisedAudio out = denoiser.run(in, SR);
            float[] produced = out == null ? null : out.getSamples();
            if (produced != null) {
                for (float v : produced) {
                    if (pending.size() > length * 8) break; // runaway guard
                    pending.add(v);
                }
            }
            for (int i = 0; i < length; i++) {
                float v = pending.isEmpty() ? pcm[offset + i] / 32768f : pending.poll();
                float scaled = Math.max(-32768f, Math.min(32767f, v * 32768f));
                pcm[offset + i] = (short) Math.round(scaled);
            }
        } catch (Throwable t) {
            degraded = true;
            LOGGER.warn("Noise suppression failed; passing mic audio through unprocessed", t);
        }
    }

    /** Drop carried-over state between utterances. */
    public void reset() {
        pending.clear();
        if (denoiser != null && !degraded) {
            try {
                denoiser.reset();
            } catch (Throwable t) {
                degraded = true;
                LOGGER.warn("Noise suppression reset failed; passing through", t);
            }
        }
    }

    /** Release native resources (capture stop). The instance is unusable after. */
    public void release() {
        if (denoiser != null && !degraded) {
            try {
                denoiser.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
