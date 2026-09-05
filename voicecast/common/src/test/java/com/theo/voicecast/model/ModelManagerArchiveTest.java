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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Path dir = temp.resolve("sherpa-zipformer-bilingual-zh-en-int8");
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
}
