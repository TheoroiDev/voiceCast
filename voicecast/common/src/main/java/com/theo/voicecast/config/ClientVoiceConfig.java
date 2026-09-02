package com.theo.voicecast.config;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side VoiceCast preferences, stored in the shared
 * {@code config/voicecast/voicecast.toml} under the {@code [client]} section.
 *
 * <p>Client-only (the player's preferred recognizer engine). Contains no
 * GLFW/LWJGL references so the common jar stays safe on a dedicated server.
 * A legacy {@code client.properties} is imported once if present.
 */
public final class ClientVoiceConfig {
    public static final String SECTION = "client";
    public static final String SECTION_COMPAT = "compat";
    public static final String ENGINE_VOSK = "vosk-text";
    /** English Vosk engine (models.json engine binding). */
    public static final String ENGINE_VOSK_EN = "vosk-en";
    /** CJK Vosk engines (models.json engine bindings; native-language recognition). */
    public static final String ENGINE_VOSK_CN = "vosk-cn";
    public static final String ENGINE_VOSK_JP = "vosk-jp";
    public static final String ENGINE_VOSK_KR = "vosk-kr";
    public static final String ENGINE_IPA = "ipa-phonemes";

    public String engine = ENGINE_VOSK;
    /** How to coexist with Simple Voice Chat when both mods want the microphone. */
    public SvcCoexistence svcCoexistence = SvcCoexistence.SHARE;

    public enum SvcCoexistence {
        /** Coexist: open the mic even while SVC is transmitting (devices are shared). */
        SHARE,
        /** Postpone opening while SVC transmits recently; falls back after a timeout. */
        DEFER;

        public static SvcCoexistence parse(String raw) {
            if (raw == null) return SHARE;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "defer" -> DEFER;
                default -> SHARE;
            };
        }
    }

    private static Path tomlFile(Path runDir) {
        return runDir.resolve("config/voicecast").resolve("voicecast.toml");
    }

    public static boolean isValidEngine(String e) {
        return ENGINE_VOSK.equals(e) || ENGINE_VOSK_EN.equals(e) || ENGINE_IPA.equals(e)
                || ENGINE_VOSK_CN.equals(e) || ENGINE_VOSK_JP.equals(e) || ENGINE_VOSK_KR.equals(e);
    }

    /**
     * Accept vosk/ipa aliases incl. language tags; null if unknown.
     * Pre-rename ids ({@code vosk-en-us}, {@code vosk-zh-cn}, {@code vosk-ja-jp},
     * {@code vosk-ko-kr}) migrate to the renamed ids so saved configs keep working.
     */
    public static String normalize(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "vosk", "text", "vosk-text", "word", "en-us", "en", "english" -> ENGINE_VOSK;
            case "vosk-en", "vosk-en-us" -> ENGINE_VOSK_EN;
            case "vosk-cn", "zh", "zh-cn", "chinese", "中文", "vosk-zh-cn" -> ENGINE_VOSK_CN;
            case "vosk-jp", "ja", "ja-jp", "japanese", "日本語", "vosk-ja-jp" -> ENGINE_VOSK_JP;
            case "vosk-kr", "ko", "ko-kr", "korean", "한국어", "vosk-ko-kr" -> ENGINE_VOSK_KR;
            case "ipa", "phoneme", "phonemes", "ipa-phonemes" -> ENGINE_IPA;
            default -> null;
        };
    }

    public static ClientVoiceConfig load(Path runDir) {
        ClientVoiceConfig c = new ClientVoiceConfig();
        Path f = tomlFile(runDir);
        Toml toml;
        if (Files.isRegularFile(f)) {
            toml = Toml.load(f);
        } else {
            toml = importLegacy(runDir);
        }
        String eng = toml.getString(SECTION, "engine", c.engine).trim();
        String norm = normalize(eng); // also migrates pre-rename ids (e.g. vosk-en-us -> vosk-en)
        if (norm != null) c.engine = norm;
        c.svcCoexistence = SvcCoexistence.parse(toml.getString(SECTION_COMPAT, "svcCoexistence", "share"));
        return c;
    }

    private static Toml importLegacy(Path runDir) {
        Toml toml = new Toml();
        Path legacy = runDir.resolve("config/voicecast").resolve("client.properties");
        if (Files.isRegularFile(legacy)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(legacy)) { p.load(in); }
            catch (IOException e) { VoiceCast.LOGGER.warn("Failed to read legacy {}", legacy, e); }
            if (p.getProperty("engine") != null) toml.setString(SECTION, "engine", p.getProperty("engine"));
            VoiceCast.LOGGER.info("Imported legacy client.properties; migrating to voicecast.toml");
        }
        return toml;
    }

    /** Persist only the {@code [client]}/{@code [compat]} sections, leaving server/other keys intact. */
    public void save(Path runDir) {
        Path f = tomlFile(runDir);
        Toml toml = Files.isRegularFile(f) ? Toml.load(f) : new Toml();
        toml.setInt("", "version", ServerConfig.SCHEMA_VERSION);
        toml.setString(SECTION, "engine", engine);
        toml.setString(SECTION_COMPAT, "svcCoexistence", svcCoexistence.name().toLowerCase(java.util.Locale.ROOT));
        toml.save(f);
        try {
            Files.deleteIfExists(runDir.resolve("config/voicecast").resolve("client.properties"));
        } catch (IOException ignored) {}
    }
}
