package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ZIPA phoneme model resolution for the {@code zipa-ipa} engine (the
 * {@code loose-files} catalog kind). Model: zipa-small-crctc-ns-no-diacritics
 * (Apache-2.0, int8, ~70 MB) — a CTC model emitting Unicode IPA phoneme
 * symbols. Files are loose (no archive): int8 weights plus the two-column
 * {@code tokens.txt} symbol list. All URLs/constraints come from
 * {@link ModelConfig} ({@code config/voicecast/models.json}).
 *
 * <p>Generic over the entry's file list: every non-optional declared file is
 * present (at its declared minimum size) or downloaded through the same
 * proxy-aware, mirror-probed pipeline as every other catalog model.
 */
public final class ZipaModel {
    /** Required CTC weights (~70 MB int8). */
    public static final String MODEL_FILE = "model.int8.onnx";
    /** Two-column kaldi symbol list ({@code <symbol> <id>}). */
    public static final String TOKENS_FILE = "tokens.txt";

    private ZipaModel() {}

    public static Path directory(Path gameDir, String modelId) {
        return gameDir.resolve("config/voicecast/models").resolve(modelId);
    }

    public static Path weightsFile(Path dir) {
        Path model = dir.resolve(MODEL_FILE);
        if (isRegularFile(model, 1)) return model;
        return null;
    }

    /** Valid when every non-optional declared file exists at its minimum size. */
    public static boolean isValidModelDir(Path dir, ModelConfig.ModelEntry entry) {
        try {
            if (!Files.isDirectory(dir)) return false;
            for (ModelConfig.FileEntry f : entry.files()) {
                if (f.optional()) continue;
                Path p = dir.resolve(f.name());
                if (!Files.isRegularFile(p) || Files.size(p) < Math.max(1, f.minBytes())) return false;
            }
            return !entry.files().isEmpty();
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

    /**
     * Content re-check of a cached file against its declared SHA-256 (R2 F-B1):
     * a cached file only needed {@code minBytes} to skip downloading, so disk
     * rot / a past partial write would survive forever. Hashing the ~70 MB
     * weights costs well under a second, so every declared file is re-verified
     * once per resolve. Package-visible for the gate unit tests.
     */
    static boolean fileMatchesSha256(Path p, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) return true;
        try {
            return ModelManager.sha256(p).equalsIgnoreCase(expectedSha256.trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether every non-optional declared file that carries a sha256 currently
     * matches it (R2 F-B1): the cache-hit gate must judge CONTENT, not just
     * size, or a corrupt file survives forever. Undeclared/missing files are
     * no opinion here — the size gate handles presence.
     */
    private static boolean cachedFilesMatchSha256(Path dir, ModelConfig.ModelEntry entry) {
        for (ModelConfig.FileEntry f : entry.files()) {
            if (f.optional() || f.sha256() == null || f.sha256().isBlank()) continue;
            Path p = dir.resolve(f.name());
            if (!Files.isRegularFile(p)) continue;
            if (!fileMatchesSha256(p, f.sha256())) return false;
        }
        return true;
    }

    /** Resolve (or download with mirror speed-test) the configured ZIPA model. */
    public static Path resolveOrDownload(Path gameDir, ModelConfig config, ModelConfig.ModelEntry entry,
                                         ModelManager.DownloadListener progress)
            throws IOException, InterruptedException {
        String modelId = entry.id();
        Path target = directory(gameDir, modelId);
        // R2 F-B1: "already installed" must pass BOTH the size gate and the
        // sha256 re-check — a size-passing, content-rotted cache used to be
        // adopted forever.
        if (isValidModelDir(target, entry) && cachedFilesMatchSha256(target, entry)) {
            VoiceCast.LOGGER.info("Using existing ZIPA model at {}", target);
            return target;
        }
        Files.createDirectories(target);
        ModelManager mgr = new ModelManager(gameDir, config.probe());
        for (ModelConfig.FileEntry f : entry.files()) {
            Path p = target.resolve(f.name());
            boolean present = isRegularFile(p, Math.max(1, f.minBytes()));
            // R2 F-B1: size alone never proved content — re-hash cached files
            // that declare a sha256 and re-download on mismatch. The corrupt
            // file is deleted first: downloadFile itself short-circuits on
            // "file exists" and would hand back the bad bytes unchanged.
            if (present && !fileMatchesSha256(p, f.sha256())) {
                VoiceCast.LOGGER.warn("Cached ZIPA file {} failed its sha256 re-check; re-downloading", p);
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                present = false;
            }
            if (!present && f.optional()) continue; // optional file: best effort only
            if (!present) {
                mgr.downloadFile(modelId, f.name(), f.urls(), f.sha256(), progress, f.minBytes());
            }
        }
        if (!isValidModelDir(target, entry)) {
            throw new IOException("Downloaded ZIPA model is missing required files: " + target);
        }
        VoiceCast.LOGGER.info("ZIPA model ready at {}", target);
        return target;
    }
}
