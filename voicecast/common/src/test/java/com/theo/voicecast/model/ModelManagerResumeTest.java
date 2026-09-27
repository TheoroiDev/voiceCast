package com.theo.voicecast.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * voiceCast#51 断点续传: a partial download ({@code *.part}) resumes with a
 * Range request instead of restarting, survives the staging wipe between
 * attempts, and the completed file passes whole-file SHA-256 verification.
 * Runs against a loopback HTTP server with real Range support.
 */
class ModelManagerResumeTest {

    @TempDir
    Path temp;

    /** Deterministic 512 KiB payload (incompressible-ish pattern so a partial
     *  file is distinguishable from padding). */
    private static byte[] payload() {
        byte[] out = new byte[512 * 1024];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (i * 31 + (i >> 9));
        }
        return out;
    }

    /** Loopback server with real Range support (single full asset + 206 for
     *  valid byte ranges). */
    private static HttpServer serveWithRange(byte[] body) throws IOException {
        HttpServer server = HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int code = 200;
            long from = 0;
            long to = body.length - 1;
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                from = Long.parseLong(parts[0]);
                if (!parts[1].isBlank()) to = Long.parseLong(parts[1]);
                code = 206;
            }
            int len = (int) (to - from + 1);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(code, len);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(Arrays.copyOfRange(body, (int) from, (int) from + len));
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void looseFileDownloadResumesFromPreexistingPart() throws IOException {
        byte[] payload = payload();
        String sha = new ModelManager(temp).sha256ForTest(payload);
        HttpServer server = serveWithRange(payload);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.bin";
            Path dir = temp.resolve("config/voicecast/models/resume-test");
            Files.createDirectories(dir);
            // Simulate an interrupted first attempt: exactly the first half of
            // the payload already on disk as the .part file.
            Path part = dir.resolve("model.bin.part");
            Files.write(part, Arrays.copyOf(payload, payload.length / 2));

            new ModelManager(temp).downloadFile("resume-test", "model.bin", url, sha, null);

            byte[] got = Files.readAllBytes(dir.resolve("model.bin"));
            assertArrayEquals(payload, got, "resumed file must equal the full payload");
            assertTrue(!Files.exists(part), "the .part file is consumed by the atomic move");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shaMismatchKeepsTargetAbsent() throws IOException {
        byte[] payload = payload();
        String wrongSha = "0".repeat(64);
        HttpServer server = serveWithRange(payload);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.bin";
            Path dir = temp.resolve("config/voicecast/models/resume-bad");
            Files.createDirectories(dir);
            Path part = dir.resolve("model.bin.part");
            Files.write(part, Arrays.copyOf(payload, payload.length / 2));

            assertThrows(IOException.class, () ->
                    new ModelManager(temp).downloadFile("resume-bad", "model.bin", url, wrongSha, null));

            assertTrue(!Files.exists(dir.resolve("model.bin")),
                    "a sha-mismatched download must not land at the target");
            assertTrue(Files.exists(part), "the part file stays for diagnosis");
        } finally {
            server.stop(0);
        }
    }
}
