package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sherpa-onnx model resolution helper (voiceCast#42). Models are distributed as
 * tar.bz2 archives (tokens.txt + per-component ONNX files); metadata comes from
 * {@link ModelConfig} ({@code kind: "sherpa-archive"}), the engine id -> model
 * binding lives in the same file. Validation: {@code tokens.txt} plus at least
 * one {@code .onnx} file in the extracted directory.
 */
public final class SherpaModel {
    private SherpaModel() {}

    public static boolean isValidModelDir(Path dir) {
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("tokens.txt"))) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(".onnx"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Resolve (or download with mirror speed-test) the given configured sherpa model. */
    public static Path resolveOrDownload(Path gameDir, ModelConfig config, ModelConfig.ModelEntry entry,
                                         ModelManager.DownloadListener progress)
            throws IOException, InterruptedException {
        String modelId = entry.id();
        Path modelsRoot = gameDir.resolve("config/voicecast/models");
        Path target = modelsRoot.resolve(modelId);
        if (isValidModelDir(target)) {
            VoiceCast.LOGGER.info("Using existing sherpa model at {}", target);
            return target;
        }
        VoiceCast.LOGGER.info("Downloading sherpa model '{}' ({} mirror URLs)...", modelId, entry.urls().size());
        Files.createDirectories(target);
        ModelManager mgr = new ModelManager(gameDir, config.probe());
        ModelManager.DownloadResult r = mgr.download(
                modelId,
                entry.urls(),
                entry.sha256(),
                entry.sizeBytes(),
                progress,
                SherpaModel::isValidModelDir,
                3);
        if (!r.ok()) {
            throw new IOException("Failed to download sherpa model: " + r.message());
        }
        if (!isValidModelDir(target)) {
            throw new IOException("Downloaded sherpa model is missing tokens.txt/.onnx files: " + target);
        }
        VoiceCast.LOGGER.info("sherpa model ready at {}", target);
        return target;
    }

    public static String describeSize(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }
}
