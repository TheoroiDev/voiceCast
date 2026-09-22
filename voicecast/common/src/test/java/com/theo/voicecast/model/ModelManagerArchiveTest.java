package com.theo.voicecast.model;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model archive extraction (voiceCast#42): sherpa-onnx ships tar.bz2 with a
 * top-level directory; the download flow must extract, delete the archive and
 * hoist the nested directory up so the probe sees tokens.txt + .onnx at the
 * model root.
 */
class ModelManagerArchiveTest {

    @TempDir
    Path temp;

    private static final ModelManager.ModelProbe SHERPA_PROBE = dir ->
            Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("tokens.txt"))
                    && SherpaModel.isValidModelDir(dir);

    private Path writeNestedSherpaTarBz2(String fileName) throws IOException {
        Path archive = temp.resolve(fileName);
        try (OutputStream fos = Files.newOutputStream(archive);
             BZip2CompressorOutputStream bz2 = new BZip2CompressorOutputStream(fos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bz2, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putTarFile(tar, "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tokens.txt", "token data");
            putTarFile(tar, "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/encoder-epoch-99-avg-1.int8.onnx", "fake onnx");
            putTarFile(tar, "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/test_wavs/README", "stray dir stays nested");
        }
        return archive;
    }

    private static void putTarFile(TarArchiveOutputStream tar, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    @Test
    void tarBz2ExtractsAndFlattensNestedDirectory() throws IOException {
        Path dir = temp.resolve("qwen3-asr-0.6b-int8");
        Files.createDirectories(dir);
        Path archive = writeNestedSherpaTarBz2("model.tar.bz2");

        ModelManager.extractArchive(archive, dir);
        ModelManager.flattenNested(dir, SHERPA_PROBE);

        assertFalse(Files.exists(archive), "archive should be deleted after extraction");
        assertTrue(SHERPA_PROBE.isValid(dir), "tokens.txt + .onnx expected at the model root");
        assertEquals("token data", Files.readString(dir.resolve("tokens.txt")));
        assertTrue(Files.isRegularFile(dir.resolve("encoder-epoch-99-avg-1.int8.onnx")));
        assertFalse(Files.exists(dir.resolve("sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20")),
                "nested top-level dir must be hoisted away");
    }

    @Test
    void tarGzExtractsToo() throws IOException {
        Path dir = temp.resolve("model-tgz");
        Files.createDirectories(dir);
        Path archive = temp.resolve("model.tar.gz");
        try (OutputStream fos = Files.newOutputStream(archive);
             java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(fos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gz, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putTarFile(tar, "top/tokens.txt", "t");
            putTarFile(tar, "top/model.int8.onnx", "o");
        }

        ModelManager.extractArchive(archive, dir);
        ModelManager.flattenNested(dir, SHERPA_PROBE);

        assertFalse(Files.exists(archive));
        assertTrue(SHERPA_PROBE.isValid(dir));
    }

    @Test
    void plainTarExtractsToo() throws IOException {
        Path dir = temp.resolve("model-plain-tar");
        Files.createDirectories(dir);
        Path archive = temp.resolve("model.tar");
        try (OutputStream fos = Files.newOutputStream(archive);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(fos, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putTarFile(tar, "top/tokens.txt", "t");
            putTarFile(tar, "top/model.int8.onnx", "o");
        }

        ModelManager.extractArchive(archive, dir);
        ModelManager.flattenNested(dir, SHERPA_PROBE);

        assertFalse(Files.exists(archive));
        assertTrue(SHERPA_PROBE.isValid(dir));
    }

    @Test
    void zipExtractsAndDeletesArchive() throws IOException {
        Path dir = temp.resolve("model-zip");
        Files.createDirectories(dir);
        Path archive = temp.resolve("model.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("vosk-model-small-en-us-0.15/conf/model.conf"));
            zip.write("conf".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("vosk-model-small-en-us-0.15/am/final.mdl"));
            zip.write("am".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        ModelManager.extractArchive(archive, dir);

        assertFalse(Files.exists(archive));
        assertTrue(Files.isRegularFile(dir.resolve("vosk-model-small-en-us-0.15/am/final.mdl")));
    }

    @Test
    void flattenSkippedWhenRootAlreadyValid() throws IOException {
        Path dir = temp.resolve("model-flat");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("tokens.txt"), "t");
        Files.writeString(dir.resolve("model.int8.onnx"), "o");
        Path nested = dir.resolve("nested");
        Files.createDirectories(nested);

        ModelManager.flattenNested(dir, SHERPA_PROBE);

        assertTrue(Files.exists(nested), "nothing should be hoisted when the root validates");
    }

    @Test
    void unknownSuffixLeftForProbe() throws IOException {
        Path dir = temp.resolve("model-bare");
        Files.createDirectories(dir);
        Path payload = dir.resolve("model_q4.onnx");
        Files.writeString(payload, "onnx");

        ModelManager.extractArchive(payload, dir);

        assertTrue(Files.exists(payload), "non-archive payloads must not be touched");
    }

    // ---------------------------------------------------------- R2 F-B1 ----
    // A download whose EXTRACTION fails (truncated archive / corrupt bz2) used
    // to leave the partially-unpacked files in the model dir: "tokens.txt +
    // half an onnx" then passed the existence probe forever and hijacked the
    // engine. The download pipeline must clear the model dir on any
    // extraction/probe failure. Local loopback HTTP server — no real download.

    /** Serves {@code body} for any GET on a random loopback port. */
    private static com.sun.net.httpserver.HttpServer serve(byte[] body) throws IOException {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    /** tar.bz2 (tokens.txt first, incompressible onnx after) cut mid-stream. */
    private static byte[] truncatedTarBz2() throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bz2 = new BZip2CompressorOutputStream(bos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bz2, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putTarFile(tar, "top/tokens.txt", "token data");
            byte[] noise = new byte[512 * 1024];
            new java.util.Random(42).nextBytes(noise); // incompressible → spans several bz2 blocks
            TarArchiveEntry e = new TarArchiveEntry("top/model.int8.onnx");
            e.setSize(noise.length);
            tar.putArchiveEntry(e);
            tar.write(noise);
            tar.closeArchiveEntry();
        }
        byte[] full = bos.toByteArray();
        return java.util.Arrays.copyOf(full, full.length * 2 / 3);
    }

    private static boolean isEmptyOrAbsent(Path dir) throws IOException {
        if (!Files.exists(dir)) return true;
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    @Test
    void truncatedArchiveDownloadClearsTargetDirectory() throws IOException {
        var server = serve(truncatedTarBz2());
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.tar.bz2";
            Path dir = temp.resolve("config/voicecast/models/qwen3-truncated");
            Files.createDirectories(dir);

            assertThrows(IOException.class, () ->
                    new ModelManager(temp).download("qwen3-truncated", List.of(url), null, -1L,
                            null, SHERPA_PROBE, 1));

            assertTrue(isEmptyOrAbsent(dir),
                    "the half-extracted directory must be cleared (R2 F-B1), not left to hijack the probe");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probeFailureAfterExtractionClearsTargetDirectory() throws IOException {
        // Valid archive that WOULD extract fine — but the probe rejects the
        // content, so the installed dir must not survive the failed download.
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bz2 = new BZip2CompressorOutputStream(bos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bz2, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putTarFile(tar, "top/tokens.txt", "token data");
            putTarFile(tar, "top/model.int8.onnx", "fake onnx");
        }
        var server = serve(bos.toByteArray());
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.tar.bz2";
            Path dir = temp.resolve("config/voicecast/models/qwen3-probe-fail");
            Files.createDirectories(dir);

            assertThrows(IOException.class, () ->
                    new ModelManager(temp).download("qwen3-probe-fail", List.of(url), null, -1L,
                            null, p -> false, 1));

            assertTrue(isEmptyOrAbsent(dir),
                    "a directory that fails its own probe must be removed, not cached forever");
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------- R2 rework: staging ----
    // The pre-rework download wrote the archive + extraction straight into the
    // install dir, so a failure-path deleteRecursively could destroy a
    // PRE-EXISTING installation (e.g. the syncVoiceModels-hardlinked run-dir
    // copy). Downloads now extract into a "<id>.download" staging sibling:
    // failure cleanup touches staging only; the install dir is replaced only
    // after the new content passed its probe.

    @Test
    void failedDownloadNeverTouchesThePreExistingInstall() throws IOException {
        var server = serve(truncatedTarBz2());
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.tar.bz2";
            Path dir = temp.resolve("config/voicecast/models/qwen3-staging-safe");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pre-existing-install.txt"), "sentinel");

            assertThrows(IOException.class, () ->
                    new ModelManager(temp).download("qwen3-staging-safe", List.of(url), null, -1L,
                            null, SHERPA_PROBE, 1));

            assertTrue(Files.isRegularFile(dir.resolve("pre-existing-install.txt")),
                    "failure cleanup must be scoped to staging, never the pre-existing install");
            assertFalse(Files.exists(dir.resolve("tokens.txt")),
                    "no extracted bytes may land in the install dir");
            assertFalse(Files.exists(dir.resolveSibling("qwen3-staging-safe.download")),
                    "staging must be cleared after the failure");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulDownloadReplacesThePreExistingInstallWithVerifiedContent() throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bz2 = new BZip2CompressorOutputStream(bos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bz2, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putTarFile(tar, "top/tokens.txt", "fresh tokens");
            putTarFile(tar, "top/model.int8.onnx", "fresh onnx");
        }
        var server = serve(bos.toByteArray());
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.tar.bz2";
            Path dir = temp.resolve("config/voicecast/models/qwen3-replace");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pre-existing-install.txt"), "old junk");

            var result = new ModelManager(temp).download("qwen3-replace", List.of(url), null, -1L,
                    null, SHERPA_PROBE, 1);

            assertTrue(result.ok());
            assertEquals("fresh tokens", Files.readString(dir.resolve("tokens.txt")));
            assertFalse(Files.exists(dir.resolve("pre-existing-install.txt")),
                    "the install is replaced only AFTER the new content passed its probe");
            assertFalse(Files.exists(dir.resolveSibling("qwen3-replace.download")),
                    "staging must not survive a successful install");
        } finally {
            server.stop(0);
        }
    }
}
