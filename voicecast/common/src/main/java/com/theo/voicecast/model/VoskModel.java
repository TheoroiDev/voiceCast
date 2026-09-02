package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Vosk model resolution helper. Model metadata (URLs, mirrors, SHA-256, size)
 * comes from {@link ModelConfig} ({@code config/voicecast/models.json}); the
 * engine id -> model binding lives in the same file.
 */
public final class VoskModel {
    /** Legacy default (kept for existing installs; the former {@code vosk-text} id now normalizes to {@code vosk-en}). */
    public static final String DEFAULT_MODEL_ID = ModelConfig.MODEL_VOSK_EN;

    private VoskModel() {}

    public static boolean isValidModelDir(Path dir) {
        return Files.isDirectory(dir)
                && Files.isDirectory(dir.resolve("conf"))
                && Files.isDirectory(dir.resolve("am"))
                && Files.isDirectory(dir.resolve("graph"));
    }

    /** Resolve (or download with mirror speed-test) the given configured Vosk model. */
    public static Path resolveOrDownload(Path gameDir, ModelConfig config, ModelConfig.ModelEntry entry,
                                         ModelManager.DownloadListener progress)
            throws IOException, InterruptedException {
        String modelId = entry.id();
        Path modelsRoot = gameDir.resolve("config/voicecast/models");
        Path target = modelsRoot.resolve(modelId);
        if (isValidModelDir(target)) {
            VoiceCast.LOGGER.info("Using existing Vosk model at {}", target);
            return target;
        }
        VoiceCast.LOGGER.info("Downloading Vosk model '{}' ({} mirror URLs)...", modelId, entry.urls().size());
        Files.createDirectories(target);
        ModelManager mgr = new ModelManager(gameDir, config.probe());
        ModelManager.DownloadResult r = mgr.download(
                modelId,
                entry.urls(),
                entry.sha256(),
                entry.sizeBytes(),
                progress,
                VoskModel::isValidModelDir,
                3);
        if (!r.ok()) {
            throw new IOException("Failed to download Vosk model: " + r.message());
        }
        if (!isValidModelDir(target)) {
            throw new IOException("Downloaded Vosk model is missing conf/am/graph: " + target);
        }
        VoiceCast.LOGGER.info("Vosk model ready at {}", target);
        return target;
    }

    public static String describeSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
