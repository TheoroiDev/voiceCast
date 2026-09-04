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
    /** sherpa-onnx streaming zipformer bilingual zh-en (canonical default). */
    public static final String ENGINE_SHERPA_ZH_EN = "sherpa-zh-en";
    /** sherpa-onnx SenseVoice offline (5-language single model). */
    public static final String ENGINE_SHERPA_SENSEVOICE = "sherpa-sensevoice";
    public static final String ENGINE_IPA = "ipa-phonemes";
    /** @deprecated vosk removed in 0.4.0; normalizes to sherpa-zh-en. */
    @Deprecated public static final String ENGINE_VOSK_EN = ENGINE_SHERPA_ZH_EN;
    /** @deprecated vosk removed in 0.4.0; normalizes to sherpa-zh-en. */
    @Deprecated public static final String ENGINE_VOSK_ZH = ENGINE_SHERPA_ZH_EN;
    /** @deprecated vosk removed in 0.4.0; normalizes to sherpa-zh-en. */
    @Deprecated public static final String ENGINE_VOSK_CN = ENGINE_SHERPA_ZH_EN;
    /** @deprecated vosk removed in 0.4.0; normalizes to sherpa-sensevoice. */
    @Deprecated public static final String ENGINE_VOSK_JA = ENGINE_SHERPA_SENSEVOICE;
    /** @deprecated vosk removed in 0.4.0; normalizes to sherpa-sensevoice. */
    @Deprecated public static final String ENGINE_VOSK_JP = ENGINE_SHERPA_SENSEVOICE;
    /** @deprecated vosk removed in 0.4.0; normalizes to sherpa-sensevoice. */
    @Deprecated public static final String ENGINE_VOSK_KO = ENGINE_SHERPA_SENSEVOICE;
    /** @deprecated vosk removed in 0.4.0; normalizes to sherpa-sensevoice. */
    @Deprecated public static final String ENGINE_VOSK_KR = ENGINE_SHERPA_SENSEVOICE;

    public String engine = ENGINE_SHERPA_ZH_EN;
    /** How to coexist with Simple Voice Chat when both mods want the microphone.
     * Only {@link SvcCoexistence#SHARE} exists; the former defer mode was removed
     * (see voicecast#27) — parsing {@code defer} falls back to SHARE with a warn. */
    public SvcCoexistence svcCoexistence = SvcCoexistence.SHARE;

    public enum SvcCoexistence {
        SHARE;
        private static boolean deferWarned;
        public static SvcCoexistence parse(String raw) {
            if (raw == null) return SHARE;
            String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (v.equals("defer") && !deferWarned) {
                deferWarned = true;
                com.theo.voicecast.VoiceCast.LOGGER.info(
                        "[compat] svcCoexistence=defer is no longer supported (removed in voicecast#27); using share");
            }
            return SHARE;
        }
    }

    private static Path tomlFile(Path runDir) {
        return runDir.resolve("config/voicecast").resolve("voicecast.toml");
    }

    public static boolean isValidEngine(String e) {
        return ENGINE_SHERPA_ZH_EN.equals(e) || ENGINE_SHERPA_SENSEVOICE.equals(e)
                || ENGINE_IPA.equals(e);
    }

    /**
     * Accept sherpa/vosk/ipa aliases incl. language tags; null if unknown.
     * All vosk-family ids ({@code vosk-text}, {@code vosk-en-us}, {@code vosk-zh-cn},
     * {@code vosk-ja-jp}, {@code vosk-ko-kr}, {@code vosk-cn/jp/kr}) migrate to
     * the current sherpa ids so saved configs keep working.
     */
    public static String normalize(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "vosk", "text", "vosk-text", "word", "en-us", "en", "english",
                 "vosk-en", "vosk-en-us", "sherpa-zh-en" -> ENGINE_SHERPA_ZH_EN;
            case "zh", "zh-cn", "cn", "chinese", "中文",
                 "vosk-zh", "vosk-cn", "vosk-zh-cn" -> ENGINE_SHERPA_ZH_EN;
            case "ja", "ja-jp", "jp", "japanese", "日本語",
                 "vosk-ja", "vosk-jp", "vosk-ja-jp" -> ENGINE_SHERPA_SENSEVOICE;
            case "ko", "ko-kr", "kr", "korean", "한국어",
                 "vosk-ko", "vosk-kr", "vosk-ko-kr" -> ENGINE_SHERPA_SENSEVOICE;
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
        String norm = normalize(eng);
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
