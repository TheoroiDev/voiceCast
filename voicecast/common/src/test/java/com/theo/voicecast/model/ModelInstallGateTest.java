package com.theo.voicecast.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Installed-model gates (R2 F-B1): the existence-only probes let a corrupted
 * INSTALLED directory (disk rot, a past partial write) survive forever —
 * sherpa dirs now need content backing ({@link SherpaModel#isPlausiblyComplete})
 * and zipa cached files are re-hashed against their declared sha256, with a
 * mismatch triggering a re-download. All HTTP is a local loopback server.
 */
class ModelInstallGateTest {

    @TempDir
    Path temp;

    private static final long MB = 1024L * 1024;

    private static ModelConfig.ModelEntry sherpaEntry(long sizeBytes) {
        return new ModelConfig.ModelEntry("gate-model", ModelConfig.KIND_SHERPA_ARCHIVE, sizeBytes, null,
                List.of("https://example.invalid/gate-model.tar.bz2"), List.of(),
                "offline", List.of("en"), Map.of(), null);
    }

    private Path dirWith(String tokens, long onnxBytes) throws IOException {
        Path dir = temp.resolve("config/voicecast/models/gate-model");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("tokens.txt"), tokens, StandardCharsets.UTF_8);
        byte[] payload = new byte[(int) onnxBytes];
        payload[0] = 'o';
        Files.write(dir.resolve("model.int8.onnx"), payload);
        return dir;
    }

    @Test
    void gateRejectsWhatTheExistenceProbeAccepts() throws IOException {
        // tokens.txt + a 10-byte .onnx: isValidModelDir says yes — exactly the
        // F-B1 hijack shape ("tokens + half an onnx").
        Path dir = dirWith("token data", 10);
        assertTrue(SherpaModel.isValidModelDir(dir), "precondition: existence probe passes");
        assertFalse(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(0)),
                "a tiny .onnx must fail the content gate");
    }

    @Test
    void gateRejectsEmptyTokens() throws IOException {
        Path dir = dirWith("", 2 * MB);
        assertFalse(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(0)));
    }

    @Test
    void gateAcceptsARealShapedDirectory() throws IOException {
        Path dir = dirWith("token data", 2 * MB);
        assertTrue(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(0)));
    }

    @Test
    void gateRejectsPartialExtractionAgainstDeclaredSize() throws IOException {
        // Declared archive 10 MB, extracted content 2 MB → a mid-archive
        // extraction (or rot), not a complete install.
        Path dir = dirWith("token data", 2 * MB);
        assertFalse(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(10 * MB)));
    }

    @Test
    void gateUsesSlackForCompressionAndNestedDirs() throws IOException {
        // Extracted content sits comfortably above the declared archive size
        // for real models (bz2 shrinks int8 weights only a few percent);
        // 1 MB declared vs 2 MB extracted must pass with the 10% slack.
        Path dir = dirWith("token data", 2 * MB);
        assertTrue(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(MB)));
    }

    // ----------------------------------------------- qwen3 tokenizer layout --
    // The official qwen3 package ships NO tokens.txt: tokens live in an
    // HF-style tokenizer/ directory (vocab.json + merges.txt +
    // tokenizer_config.json). The old tokens.txt-only predicate permanently
    // mis-rejected the primary production model (R2 F-B1 review finding) —
    // these tests pin the layout-aware gate in both directions.

    private static Path realQwen3Dir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve("resources/models/qwen3-asr-0.6b-int8");
            if (Files.isRegularFile(cand.resolve("encoder.int8.onnx"))) return cand;
        }
        return null;
    }

    /** Mirrors the ModelConfig built-in default for qwen3-asr-0.6b-int8. */
    private static ModelConfig.ModelEntry realQwen3Entry() {
        return new ModelConfig.ModelEntry("qwen3-asr-0.6b-int8", ModelConfig.KIND_SHERPA_ARCHIVE,
                878_702_423L, null,
                List.of("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                        + "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"),
                List.of(), "offline", List.of("en", "zh"), Map.of(), null);
    }

    /** Refine 2026-09-27: this fixture used to hand-copy the catalog's
     *  qwen3 entry and drift silently. Pin it to the shipped default. */
    @Test
    void fixtureMatchesShippedQwen3CatalogEntry() {
        ModelConfig cfg = ModelConfig.load(Path.of("build/test-catalog-fixture"));
        ModelConfig.ModelEntry shipped = cfg.model("qwen3-asr-0.6b-int8");
        var fixture = realQwen3Entry();
        org.junit.jupiter.api.Assertions.assertEquals(shipped.id(), fixture.id());
        org.junit.jupiter.api.Assertions.assertEquals(shipped.kind(), fixture.kind());
        org.junit.jupiter.api.Assertions.assertEquals(shipped.sizeBytes(), fixture.sizeBytes());
        org.junit.jupiter.api.Assertions.assertEquals(shipped.urls(), fixture.urls());
    }

    @Test
    void realWorkspaceQwen3DirPassesTheGate() throws IOException {
        Path dir = realQwen3Dir();
        assumeTrue(dir != null, "workspace resources/models/qwen3-asr-0.6b-int8 not found");
        assertTrue(SherpaModel.isValidModelDir(dir),
                "the existence probe must accept the tokenizer-dir layout");
        assertTrue(SherpaModel.isPlausiblyComplete(dir, realQwen3Entry()),
                "the real production model dir must pass the content gate (R2 F-B1 rework)");
    }

    @Test
    void gateRejectsTokenizerDirWithoutVocabFiles() throws IOException {
        // A tokenizer dir that only carries incidental files (or an empty
        // vocab marker) is not a tokens equivalent.
        Path dir = temp.resolve("config/voicecast/models/gate-model");
        Files.createDirectories(dir.resolve("tokenizer"));
        Files.write(dir.resolve("model.int8.onnx"), new byte[(int) (2 * MB)]);
        assertFalse(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(0)),
                "an empty tokenizer dir is not a tokens equivalent");

        Files.write(dir.resolve("tokenizer/vocab.json"), new byte[0]);
        assertFalse(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(0)),
                "a 0-byte vocab marker does not qualify either");
    }

    @Test
    void gateAcceptsTokenizerDirWithRealVocab() throws IOException {
        Path dir = temp.resolve("config/voicecast/models/gate-model");
        Files.createDirectories(dir.resolve("tokenizer"));
        Files.writeString(dir.resolve("tokenizer/merges.txt"), "#version: 0.2", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("tokenizer/tokenizer_config.json"), "{}", StandardCharsets.UTF_8);
        Files.write(dir.resolve("model.int8.onnx"), new byte[(int) (2 * MB)]);
        assertTrue(SherpaModel.isPlausiblyComplete(dir, sherpaEntry(0)),
                "a tokenizer dir with a real vocab file is the qwen3 tokens equivalent");
    }

    // ------------------------------------------------------------ zipa sha --

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void zipaShaPredicateMatchesAndRejects() throws Exception {
        Path p = temp.resolve("tokens.txt");
        byte[] good = "sym 0\n".getBytes(StandardCharsets.UTF_8);
        Files.write(p, good);
        assertTrue(ZipaModel.fileMatchesSha256(p, sha256(good)));
        assertFalse(ZipaModel.fileMatchesSha256(p, sha256("other".getBytes(StandardCharsets.UTF_8))));
        assertTrue(ZipaModel.fileMatchesSha256(p, null), "undeclared sha = no opinion");
        assertFalse(ZipaModel.fileMatchesSha256(temp.resolve("missing.txt"), sha256(good)));
    }

    /** Serves {@code body} at any path on a random loopback port. */
    private HttpServer serve(byte[] body) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void corruptZipaCacheIsReDownloadedFromTheUrl() throws Exception {
        byte[] good = "correct tokens\n".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(good);
        server = serve(good);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/tokens.txt";

        ModelConfig.ModelEntry entry = new ModelConfig.ModelEntry("zipa-gate", ModelConfig.KIND_LOOSE_FILES,
                0, null, List.of(),
                List.of(new ModelConfig.FileEntry("tokens.txt", List.of(url), sha, 1, false)),
                "ipa", List.of(), Map.of(), null);

        Path gameDir = temp.resolve("game");
        Path cache = ZipaModel.directory(gameDir, "zipa-gate");
        Files.createDirectories(cache);
        // Corrupt cache: right size (≥ minBytes), wrong content.
        Files.writeString(cache.resolve("tokens.txt"), "corrupted garbage that satisfies minBytes",
                StandardCharsets.UTF_8);

        ModelConfig config = ModelConfig.load(temp.resolve("catalog-run"));
        Path resolved = ZipaModel.resolveOrDownload(gameDir, config, entry, null);

        assertArrayEquals(good, Files.readString(resolved.resolve("tokens.txt"), StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8), "sha re-check must force the re-download");
    }
}
