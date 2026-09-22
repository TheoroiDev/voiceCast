package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sherpa-onnx model resolution helper (voiceCast#42). Models are distributed as
 * tar.bz2 archives; metadata comes from {@link ModelConfig} ({@code kind:
 * "sherpa-archive"}), the engine id -> model binding lives in the same file.
 *
 * <p>Two shipped layouts exist and the gate accepts BOTH (R2 F-B1 rework —
 * the old tokens.txt-only predicate permanently mis-rejected the primary
 * production model, re-downloading it on every start):
 * <ul>
 *   <li>classic sherpa packages: a {@code tokens.txt} at the model root
 *       (zipformer/sensevoice family, whose engine config points {@code tokens}
 *       at that file);</li>
 *   <li>the official qwen3 package: NO tokens.txt — tokens live in an
 *       HF-style {@code tokenizer/} directory (vocab.json + merges.txt +
 *       tokenizer_config.json) that the recognizer loads as a whole
 *       ({@code setTokenizer(modelDir/"tokenizer")}).</li>
 * </ul>
 * Validation: a tokens equivalent (either layout) plus at least one
 * {@code .onnx} file in the extracted directory.
 */
public final class SherpaModel {
    private SherpaModel() {}

    /**
     * Absolute floor for "a real ONNX payload" (R2 F-B1): every ASR
     * encoder/decoder in practice is far larger; a truncated extract is not.
     * Applied to the LARGEST .onnx in the dir, so a small auxiliary onnx next
     * to the big weights never breaks the gate.
     */
    private static final long MIN_ONNX_BYTES = 1L << 20;

    /**
     * Files a HF-style {@code tokenizer/} directory must offer (any one) to
     * count as a tokens equivalent (R2 F-B1 rework). Deliberately a whitelist
     * of the files an HF tokenizer actually reads — an incidental file in a
     * truncated tokenizer dir does not qualify.
     */
    private static final String[] TOKENIZER_DIR_MARKERS = {"vocab.json", "merges.txt", "tokenizer.json"};

    /** A tokens equivalent at {@code dir}: tokens.txt, or a tokenizer dir with a real vocab file. */
    static boolean hasTokensEquivalent(Path dir) {
        if (Files.isRegularFile(dir.resolve("tokens.txt"))) return true;
        Path tok = dir.resolve("tokenizer");
        if (!Files.isDirectory(tok)) return false;
        for (String marker : TOKENIZER_DIR_MARKERS) {
            if (Files.isRegularFile(tok.resolve(marker))) return true;
        }
        return false;
    }

    /** Content backing for the tokens equivalent: 0-byte markers do not qualify. */
    private static boolean hasNonEmptyTokens(Path dir) throws IOException {
        Path tokens = dir.resolve("tokens.txt");
        if (Files.isRegularFile(tokens)) return Files.size(tokens) > 0;
        Path tok = dir.resolve("tokenizer");
        for (String marker : TOKENIZER_DIR_MARKERS) {
            Path p = tok.resolve(marker);
            if (Files.isRegularFile(p) && Files.size(p) > 0) return true;
        }
        return false;
    }

    public static boolean isValidModelDir(Path dir) {
        if (!Files.isDirectory(dir) || !hasTokensEquivalent(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(".onnx"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Installed-dir gate with content backing (R2 F-B1). The bare
     * {@link #isValidModelDir} probe only checks file EXISTENCE, so a tar.bz2
     * extraction that died mid-archive leaves a tokens equivalent + a
     * half-written .onnx — which then hijacks the engine permanently (every
     * start finds an "installed" model and never re-downloads). This gate
     * additionally requires: a non-empty tokens equivalent (either layout —
     * tokens.txt, or the qwen3 tokenizer dir with a real vocab file), at
     * least one .onnx of plausible size, and — when the catalog declares the
     * archive size ({@code size_bytes}) — extracted content not far below it
     * (bzip2 on int8 weights shrinks only a few percent, so the 10% slack
     * covers nested dirs like test_wavs/tokenizer while still rejecting any
     * meaningful truncation).
     */
    public static boolean isPlausiblyComplete(Path dir, ModelConfig.ModelEntry entry) {
        if (!isValidModelDir(dir)) return false;
        try {
            if (!hasNonEmptyTokens(dir)) return false;
            long total = 0;
            boolean hasRealOnnx = false;
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(Files::isRegularFile).toList()) {
                    long sz = Files.size(p);
                    total += sz;
                    if (p.getFileName().toString().endsWith(".onnx") && sz >= MIN_ONNX_BYTES) {
                        hasRealOnnx = true;
                    }
                }
            }
            if (!hasRealOnnx) return false;
            long declared = entry == null ? 0 : entry.sizeBytes();
            if (declared > 0 && total < (declared / 10L) * 9L) return false;
            return true;
        } catch (IOException e) {
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
        // R2 F-B1: the "already installed" fast path uses the content-backed
        // gate — an existence-only check is what let a half-extracted dir
        // hijack the engine permanently.
        if (isPlausiblyComplete(target, entry)) {
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
        if (!isPlausiblyComplete(target, entry)) {
            // Post-install re-check (R2 F-B1): the retry loop's probe is
            // existence-only; this is where content is actually judged.
            throw new IOException("Downloaded sherpa model is incomplete (tokens-equivalent/.onnx content gate): " + target);
        }
        VoiceCast.LOGGER.info("sherpa model ready at {}", target);
        return target;
    }

    /**
     * Human-readable size for live progress: MB is kept all the way to 10 GB
     * so the counter visibly ticks every ~1 MB (a "%.1f GB" display would sit
     * frozen on the same value for ~100 MB stretches, reading as a stall).
     */
    public static String describeSize(long bytes) {
        if (bytes >= 10L << 30) return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }
}
