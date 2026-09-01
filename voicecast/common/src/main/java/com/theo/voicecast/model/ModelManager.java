package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads and verifies speech-model archives. Engines call {@link #download}
 * with their own URL and marker-check via {@link ModelProbe}.
 *
 * <p>When a model lists more than one URL (mirrors), {@link #rankMirrors}
 * probes every mirror concurrently with a small ranged GET and ranks them by
 * measured throughput (TTFB as tie-break); downloads then try the fastest
 * mirror first and the rest as fallbacks.
 */
public final class ModelManager {
    private final Path root;
    private final ModelConfig.MirrorProbe probe;

    public ModelManager(Path gameDir) {
        this(gameDir, ModelConfig.MirrorProbe.DEFAULT);
    }

    public ModelManager(Path gameDir, ModelConfig.MirrorProbe probe) {
        this.root = gameDir.resolve("config/voicecast/models");
        this.probe = probe == null ? ModelConfig.MirrorProbe.DEFAULT : probe;
    }

    /**
     * Open an HTTP(S) connection honoring proxies. Java's HttpURLConnection reads
     * the {@code -Dhttp(s).proxyHost} JVM properties but NOT the {@code HTTPS_PROXY}
     * environment variables, so behind a local proxy (e.g. Clash/v2ray at
     * 127.0.0.1:10808) downloads would silently go direct and crawl. We detect
     * https_proxy/HTTPS_PROXY/http_proxy (and the JVM props) and apply them.
     */
    static HttpURLConnection openConnection(URI uri) throws IOException {
        return (HttpURLConnection) uri.toURL().openConnection(detectProxy());
    }

    static Proxy detectProxy() {
        // Explicit JVM proxy settings win.
        String host = System.getProperty("https.proxyHost", System.getProperty("http.proxyHost"));
        String portStr = System.getProperty("https.proxyPort", System.getProperty("http.proxyPort", "80"));
        if (host == null || host.isBlank()) {
            // Fall back to environment variables (standard lowercase/uppercase).
            String env = System.getenv().getOrDefault("HTTPS_PROXY",
                    System.getenv().getOrDefault("https_proxy",
                            System.getenv().getOrDefault("HTTP_PROXY",
                                    System.getenv().get("http_proxy"))));
            if (env != null && !env.isBlank()) {
                try {
                    URI pu = URI.create(env.contains("://") ? env : "http://" + env);
                    host = pu.getHost();
                    portStr = pu.getPort() > 0 ? String.valueOf(pu.getPort()) : "80";
                } catch (Exception e) {
                    VoiceCast.LOGGER.warn("Could not parse proxy env '{}': {}", env, e.getMessage());
                }
            }
        }
        if (host == null || host.isBlank()) return Proxy.NO_PROXY;
        try {
            int port = Integer.parseInt(portStr.trim());
            VoiceCast.LOGGER.info("Using proxy {}:{} for model downloads", host, port);
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        } catch (NumberFormatException e) {
            VoiceCast.LOGGER.warn("Invalid proxy port '{}', downloading direct", portStr);
            return Proxy.NO_PROXY;
        }
    }

    public Path root() { return root; }

    public record DownloadResult(Path path, boolean ok, String message) {}

    public interface ModelProbe {
        boolean isValid(Path dir);
    }

    public interface DownloadListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    public DownloadResult download(String modelId, String url, String expectedSha256,
                                   DownloadListener progress) throws IOException {
        return download(modelId, url, expectedSha256, progress, d -> true);
    }

    public DownloadResult download(String modelId, String url, String expectedSha256,
                                   DownloadListener progress, ModelProbe probe) throws IOException {
        return download(modelId, url, expectedSha256, progress, probe, 3);
    }

    public DownloadResult download(String modelId, String url, String expectedSha256,
                                   DownloadListener progress, ModelProbe probe,
                                   int maxAttempts) throws IOException {
        return download(modelId, List.of(url), expectedSha256, -1L, progress, probe, maxAttempts);
    }

    /** Multi-mirror archive download: mirrors are speed-probed, fastest first. */
    public DownloadResult download(String modelId, List<String> urls, String expectedSha256,
                                   long expectedBytes, DownloadListener progress, ModelProbe probe,
                                   int maxAttempts) throws IOException {
        if (urls == null || urls.isEmpty()) throw new IOException("No download URLs configured for " + modelId);
        List<String> ranked = rankMirrors(urls, expectedBytes);
        Path dir = root.resolve(modelId);
        Files.createDirectories(dir);
        Path archive = dir.resolve(archiveName(ranked.get(0)));

        IOException lastError = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return downloadOnce(modelId, ranked, expectedSha256, progress, probe, dir, archive, attempt, maxAttempts);
            } catch (IOException e) {
                lastError = e;
                VoiceCast.LOGGER.warn("Download attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                // Clean up partial archive before retrying.
                Files.deleteIfExists(archive);
                if (attempt < maxAttempts) {
                    try {
                        long backoff = Math.min(15_000L, 1_000L * (1L << attempt));
                        VoiceCast.LOGGER.info("Retrying in {} ms...", backoff);
                        //noinspection BusyWait
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted", ie);
                    }
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Download failed");
    }

    /**
     * Download a single raw file (no archive extraction) into {@code models/<modelId>/<fileName>},
     * trying each URL in order as mirrors. Used by models that ship loose files
     * (e.g. an .onnx weights file + a .json vocab).
     */
    public Path downloadFile(String modelId, String fileName, List<String> urls, String expectedSha256,
                             DownloadListener progress) throws IOException {
        return downloadFile(modelId, fileName, urls, expectedSha256, progress, -1);
    }

    /**
     * Loose-file download with an expected size hint; files at least
     * {@code mirrorProbe.minFileSizeBytes} get their mirrors speed-probed and
     * are fetched fastest-first.
     */
    public Path downloadFile(String modelId, String fileName, List<String> urls, String expectedSha256,
                             DownloadListener progress, long expectedBytes) throws IOException {
        Path dir = root.resolve(modelId);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            VoiceCast.LOGGER.info("Using existing model file {}", target);
            return target;
        }
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                fetchWithMirrors(target, urls, expectedSha256, progress, expectedBytes);
                return target;
            } catch (IOException e) {
                last = e;
                VoiceCast.LOGGER.warn("File download {}/{} failed: {}", attempt, 3, e.getMessage());
                Files.deleteIfExists(target);
                if (attempt < 3) {
                    try {
                        Thread.sleep(Math.min(15_000L, 1_000L * (1L << attempt)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted", ie);
                    }
                }
            }
        }
        throw last != null ? last : new IOException("Download failed");
    }

    /** Single-URL convenience wrapper around {@link #downloadFile(String, String, List, String, DownloadListener)}. */
    public Path downloadFile(String modelId, String fileName, String url, String expectedSha256,
                             DownloadListener progress) throws IOException {
        return downloadFile(modelId, fileName, List.of(url), expectedSha256, progress);
    }

    /**
     * Single, shared HTTP fetch: opens the connection honoring proxies, streams
     * the response to {@code target} with progress callbacks, and optionally
     * verifies SHA-256. Both the loose-file downloads (IPA weights/vocab) and the
     * archive downloads (Vosk zip) route through this so proxy/retry behaviour
     * is identical for every model.
     */
    private void fetchUrlToFile(String url, Path target, String expectedSha256,
                                DownloadListener progress) throws IOException {
        HttpURLConnection conn = openConnection(URI.create(url));
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "VoiceCast/0.1.0");
        long total = conn.getContentLengthLong();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        long done;
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[65536];
            done = 0;
            long lastReport = 0;
            try (var out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    done += r;
                    if (progress != null && done - lastReport > 1_000_000) {
                        progress.onProgress(done, total);
                        lastReport = done;
                    }
                }
            }
            if (progress != null) progress.onProgress(done, total);
        } finally {
            conn.disconnect();
        }
        // Treat an empty (or truncated) body as a failure so callers retry / fall
        // back to another mirror instead of silently shipping a 0-byte model.
        if (done == 0) {
            throw new IOException("Downloaded 0 bytes from " + url);
        }
        if (total > 0 && done < total) {
            throw new IOException("Truncated download from " + url + " (" + done + "/" + total + " bytes)");
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actual = sha256(target);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("SHA256 mismatch for " + target.getFileName()
                        + ": expected " + expectedSha256 + " got " + actual);
            }
        }
    }

    /**
     * Try each mirror URL in order until one completes the download to
     * {@code target}; used for loose files (e.g. IPA weights on hf-mirror vs
     * huggingface.co). Proxy/retry handling is inside {@link #fetchUrlToFile}.
     * When the expected file is large enough, mirrors are speed-probed first
     * and the fastest one is tried first.
     */
    private void fetchWithMirrors(Path target, List<String> urls, String expectedSha256,
                                  DownloadListener progress) throws IOException {
        fetchWithMirrors(target, urls, expectedSha256, progress, -1);
    }

    private void fetchWithMirrors(Path target, List<String> urls, String expectedSha256,
                                  DownloadListener progress, long expectedBytes) throws IOException {
        List<String> ranked = rankMirrors(urls, expectedBytes);
        IOException last = null;
        for (String url : ranked) {
            try {
                fetchUrlToFile(url, target, expectedSha256, progress);
                return;
            } catch (IOException e) {
                last = e;
                VoiceCast.LOGGER.warn("Download from {} failed: {}", url, e.getMessage());
                Files.deleteIfExists(target);
            }
        }
        throw last != null ? last : new IOException("No download URLs available");
    }

    // ---------------------------------------------------------------- mirrors

    private record ProbeResult(String url, double throughputBytesPerMs, double ttfbMs, Throwable error) {}

    /**
     * Probe every mirror concurrently with a ranged GET and return the URLs
     * sorted fastest-first (throughput desc, TTFB asc; failures last).
     * Probing is skipped when there is only one URL, when disabled in config,
     * or when the expected file is small and ordering doesn't matter.
     */
    public List<String> rankMirrors(List<String> urls, long expectedBytes) {
        if (urls == null || urls.size() < 2 || !probe.enabled()) return urls == null ? List.of() : urls;
        if (expectedBytes >= 0 && expectedBytes < probe.minFileSizeBytes()) return urls;
        long probeBytes = Math.min(probe.probeBytes(), Math.max(4096, expectedBytes));
        long timeoutMs = probe.timeoutMs();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(urls.size(), 8), r -> {
            Thread t = new Thread(r, "VoiceCast-MirrorProbe");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<ProbeResult>> futures = new ArrayList<>();
            for (String url : urls) {
                futures.add(CompletableFuture.supplyAsync(() -> probeMirror(url, probeBytes, timeoutMs), pool));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            List<ProbeResult> results = new ArrayList<>(futures.size());
            for (CompletableFuture<ProbeResult> f : futures) results.add(f.join());
            results.sort(Comparator
                    .comparing((ProbeResult r) -> r.error() != null)          // successes first
                    .thenComparing(r -> r.error() != null ? r.throughputBytesPerMs() : -r.throughputBytesPerMs())
                    .thenComparing(ProbeResult::ttfbMs));
            List<String> ranked = new ArrayList<>(results.size());
            for (ProbeResult r : results) {
                if (r.error() == null) {
                    VoiceCast.LOGGER.info(String.format(Locale.ROOT,
                            "Mirror probe %s: %.0f KB/s, ttfb %.0f ms", r.url(),
                            r.throughputBytesPerMs(), r.ttfbMs()));
                } else {
                    VoiceCast.LOGGER.warn("Mirror probe {} failed: {}", r.url(), String.valueOf(r.error().getMessage()));
                }
                ranked.add(r.url());
            }
            VoiceCast.LOGGER.info("Fastest mirror: {}", ranked.get(0));
            return ranked;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One ranged GET: read up to {@code bytes} and measure throughput + TTFB. */
    private ProbeResult probeMirror(String url, long bytes, long timeoutMs) {
        HttpURLConnection conn = null;
        long t0 = System.nanoTime();
        double ttfbMs = Double.MAX_VALUE;
        long read = 0;
        try {
            conn = openConnection(URI.create(url));
            conn.setConnectTimeout((int) timeoutMs);
            conn.setReadTimeout((int) timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "VoiceCast/0.1.0");
            conn.setRequestProperty("Range", "bytes=0-" + (bytes - 1));
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return new ProbeResult(url, 0, Double.MAX_VALUE, new IOException("HTTP " + code));
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                long deadline = t0 + timeoutMs * 1_000_000L;
                int r;
                while (read < bytes && (r = in.read(buf)) > 0) {
                    if (ttfbMs == Double.MAX_VALUE) {
                        ttfbMs = (System.nanoTime() - t0) / 1_000_000.0;
                    }
                    read += r;
                    if (System.nanoTime() > deadline) break;
                }
            }
            if (read == 0) return new ProbeResult(url, 0, ttfbMs, new IOException("0 bytes"));
            double ms = Math.max(1.0, (System.nanoTime() - t0) / 1_000_000.0);
            return new ProbeResult(url, read / ms, ttfbMs, null);
        } catch (Throwable t) {
            return new ProbeResult(url, 0, ttfbMs, t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private DownloadResult downloadOnce(String modelId, List<String> rankedUrls, String expectedSha256,
                                        DownloadListener progress, ModelProbe probe,
                                        Path dir, Path archive,
                                        int attempt, int maxAttempts) throws IOException {
        VoiceCast.LOGGER.info("Downloading {} -> {} (attempt {}/{}, fastest mirror first of {})",
                modelId, archive, attempt, maxAttempts, rankedUrls.size());
        IOException last = null;
        boolean fetched = false;
        for (String url : rankedUrls) {
            try {
                fetchUrlToFile(url, archive, expectedSha256, progress);
                fetched = true;
                break;
            } catch (IOException e) {
                last = e;
                VoiceCast.LOGGER.warn("Download from {} failed: {}", url, e.getMessage());
                Files.deleteIfExists(archive);
            }
        }
        if (!fetched) throw last != null ? last : new IOException("No mirror succeeded");

        if (archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            unzip(archive, dir);
            Files.deleteIfExists(archive);
        }

        if (!probe.isValid(dir)) {
            try (var entries = Files.list(dir)) {
                var nested = entries
                        .filter(Files::isDirectory)
                        .filter(probe::isValid)
                        .findFirst()
                        .orElse(null);
                if (nested != null) {
                    Path tmp = dir.resolveSibling(dir.getFileName() + ".flatten");
                    if (Files.exists(tmp)) deleteRecursively(tmp);
                    Files.move(nested, tmp);
                    deleteRecursively(dir);
                    Files.move(tmp, dir);
                    VoiceCast.LOGGER.info("Flattened nested model directory {}", nested.getFileName());
                }
            }
        }

        boolean ok = probe.isValid(dir);
        if (!ok) {
            throw new IOException("Downloaded model is missing expected files in " + dir);
        }
        return new DownloadResult(dir, true, "ok");
    }

    private static String archiveName(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash < 0 ? url : url.substring(slash + 1);
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        return name.isBlank() ? "model.zip" : name;
    }

    private static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IOException("SHA256 not available", e);
        }
    }

    private static void unzip(Path archive, Path dest) throws IOException {
        Path destAbs = dest.toAbsolutePath().normalize();
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                // Skip empty / root directory entries (e.g. "vosk-model-small-en-us-0.15/").
                if (name.isBlank() || name.equals("/") || name.endsWith("/") && name.indexOf('/') == name.length() - 1) {
                    if (!name.isBlank() && name.length() > 1) {
                        Path d = destAbs.resolve(name.substring(0, name.length() - 1)).normalize();
                        if (d.startsWith(destAbs)) Files.createDirectories(d);
                    }
                    continue;
                }
                Path out = destAbs.resolve(name).normalize();
                if (!out.startsWith(destAbs)) {
                    throw new IOException("Bad zip entry " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public List<Path> installedModels() {
        try {
            if (!Files.isDirectory(root)) return List.of();
            try (var s = Files.list(root)) {
                return s.filter(Files::isDirectory).toList();
            }
        } catch (IOException e) {
            VoiceCast.LOGGER.warn("Could not list installed models", e);
            return List.of();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
        }
    }
}

