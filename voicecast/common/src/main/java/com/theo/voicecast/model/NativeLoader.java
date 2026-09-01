package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads bundled native libraries from the mod jar (under
 * {@code /natives/<os>-<arch>/}) into a per-game cache directory and
 * invokes {@link System#load(String)}. Each engine calls this once during
 * {@code start()} to avoid relying on {@code java.library.path}.
 */
public final class NativeLoader {
    private NativeLoader() {}

    public static final class Platform {
        public final String os;
        public final String arch;
        public final String libPrefix;
        public final String libSuffix;

        private Platform(String os, String arch, String libPrefix, String libSuffix) {
            this.os = os;
            this.arch = arch;
            this.libPrefix = libPrefix;
            this.libSuffix = libSuffix;
        }

        public String resourceDir() { return "/natives/" + os + "-" + arch + "/"; }

        public static Platform detect() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String arch = switch (osArch) {
                case "amd64", "x86_64" -> "x86_64";
                case "aarch64", "arm64" -> "arm64";
                case "x86", "i386", "i686" -> "x86";
                default -> osArch;
            };
            if (osName.contains("win")) return new Platform("windows", arch, "", ".dll");
            if (osName.contains("mac") || osName.contains("darwin")) return new Platform("macos", arch, "lib", ".dylib");
            if (osName.contains("nux") || osName.contains("nix")) return new Platform("linux", arch, "lib", ".so");
            return new Platform("unknown", arch, "lib", ".so");
        }
    }

    public static void loadLibrary(Path cacheRoot, String baseName) throws IOException {
        Platform p = Platform.detect();
        String fileName = p.libPrefix + baseName + p.libSuffix;
        String resourcePath = p.resourceDir() + fileName;
        Path targetDir = cacheRoot.resolve(p.os + "-" + p.arch);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(fileName);

        try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Native library not bundled: " + resourcePath
                        + " (platform " + p.os + "-" + p.arch + ")");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        VoiceCast.LOGGER.info("Loading native library {}", target);
        System.load(target.toAbsolutePath().toString());
    }
}
