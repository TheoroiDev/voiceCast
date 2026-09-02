package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * IPA phoneme model resolution for the {@code ipa-phonemes} engine.
 *
 * <p>Uses wav2vec2-lv-60-espeak-cv-ft (Meta/Apache-2.0), ONNX-converted by
 * onnx-community. CTC model emitting Unicode IPA phoneme tokens. Files are
 * loose (no archive): int4 q4 weights (~230 MB) plus {@code vocab.json}. All
 * URLs/constraints come from {@link ModelConfig} ({@code config/voicecast/models.json}).
 */
public final class IpaModel {
    public static final String MODEL_ID = ModelConfig.MODEL_IPA;

    /** Required weights: block-wise int4 from the official repo (~230 MB). */
    public static final String Q4_FILE = "model_q4.onnx";
    public static final String VOCAB_FILE = "vocab.json";

    private static final long MIN_Q4_BYTES = 150L * 1024 * 1024;

    private IpaModel() {}

    public static Path directory(Path gameDir, String modelId) {
        return gameDir.resolve("config/voicecast/models").resolve(modelId);
    }

    /** The weights file actually present (q4), or null. */
    public static Path weightsFile(Path dir) {
        Path q4 = dir.resolve(Q4_FILE);
        if (isRegularFile(q4, MIN_Q4_BYTES)) return q4;
        return null;
    }

    public static boolean isValidModelDir(Path dir) {
        try {
            return Files.isDirectory(dir)
                    && weightsFile(dir) != null
                    && Files.isRegularFile(dir.resolve(VOCAB_FILE))
                    && Files.size(dir.resolve(VOCAB_FILE)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isRegularFile(Path p, long minBytes) {
        try {
            return Files.isRegularFile(p) && Files.size(p) >= minBytes;
        } catch (IOException e) {
            return false;
        }
    }

    /** Resolve (or download with mirror speed-test) the configured IPA model. */
    public static Path resolveOrDownload(Path gameDir, ModelConfig config, ModelConfig.ModelEntry entry,
                                         ModelManager.DownloadListener progress)
            throws IOException, InterruptedException {
        String modelId = entry.id();
        Path target = directory(gameDir, modelId);
        if (isValidModelDir(target)) {
            VoiceCast.LOGGER.info("Using existing IPA model at {}", target);
            return target;
        }
        Files.createDirectories(target);
        ModelManager mgr = new ModelManager(gameDir, config.probe());

        // 1) vocab (tiny) - mirror list goes through the single proxy-aware
        //    downloadFile path, so IPA files honor the same proxy as Vosk.
        //    Too small to be worth mirror speed-testing.
        for (ModelConfig.FileEntry f : entry.files()) {
            if (IpaModel.VOCAB_FILE.equals(f.name()) || f.name().endsWith(".json")) {
                mgr.downloadFile(modelId, f.name(), f.urls(), f.sha256(), progress, 0);
            }
        }

        // 2) weights: official int4 q4 (~230 MB, speed-tested across mirrors).
        //    No float32 fallback: only the q4 file is accepted.
        if (weightsFile(target) == null) {
            ModelConfig.FileEntry q4 = null;
            for (ModelConfig.FileEntry f : entry.files()) {
                if (Q4_FILE.equals(f.name())) q4 = f;
            }
            if (q4 == null) {
                throw new IOException("IPA model entry has no " + Q4_FILE + " file configured");
            }
            mgr.downloadFile(modelId, q4.name(), q4.urls(), q4.sha256(), progress, MIN_Q4_BYTES);
        }

        if (!isValidModelDir(target)) {
            throw new IOException("Downloaded IPA model is missing weights/vocab");
        }
        Path w = weightsFile(target);
        VoiceCast.LOGGER.info("IPA model ready at {} ({})", target, w.getFileName());
        return target;
    }
}
