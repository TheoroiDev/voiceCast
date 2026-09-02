package com.theo.voicecast.config;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Server-side VoiceCast configuration, stored in
 * {@code config/voicecast/voicecast.toml} (shared file; server reads the
 * {@code [server]} / {@code [engines]} sections). Decides which recognizer is
 * warmed by default and whether models auto-download.
 *
 * <p>Schema is versioned ({@code version} key); missing keys are filled with
 * defaults and the file is re-written. A legacy {@code server.properties} is
 * imported once if present.
 */
public final class ServerConfig {
    public static final String SECTION = "server";
    public static final int SCHEMA_VERSION = 1;

    public String engine = "vosk-text";      // vosk-text | vosk-en | vosk-cn | vosk-jp | vosk-kr | ipa-phonemes | noop
    public boolean autoDownload = true;
    public int maxFramesPerSecond = 15;
    public List<String> allowedEngines = DEFAULT_ALLOWED_ENGINES;
    /** Master switch: when false, no player may stream audio (models stay unloaded). */
    public boolean enabled = true;
    /** {@code [players] whitelist} of raw UUID strings; empty = everyone. */
    public List<String> whitelist = List.of();

    /** Full builtin engine whitelist (one-time upgrades of older default lists land here). */
    public static final List<String> DEFAULT_ALLOWED_ENGINES = List.of(
            "vosk-text", "vosk-en", "vosk-cn", "vosk-jp", "vosk-kr", "ipa-phonemes");

    /** Pre-vosk-en-us default whitelist; upgraded once on load. */
    private static final List<String> LEGACY_DEFAULT_ENGINES = List.of("vosk-text", "ipa-phonemes");
    /** Pre-CJK default whitelist; upgraded once on load. Customized lists are left alone. */
    private static final List<String> LEGACY_DEFAULT_PRE_CJK = List.of("vosk-text", "vosk-en-us", "ipa-phonemes");
    /** Default whitelist using the pre-rename CJK ids (vosk-zh-cn/...); upgraded once on load. */
    private static final List<String> LEGACY_DEFAULT_PRE_RENAME = List.of(
            "vosk-text", "vosk-en-us", "vosk-zh-cn", "vosk-ja-jp", "vosk-ko-kr", "ipa-phonemes");

    private ServerConfig() {}

    private static Path tomlFile(Path runDir) {
        return runDir.resolve("config/voicecast").resolve("voicecast.toml");
    }

    public static ServerConfig load(Path runDir) {
        ServerConfig c = new ServerConfig();
        Path f = tomlFile(runDir);
        Toml toml;
        if (Files.isRegularFile(f)) {
            toml = Toml.load(f);
        } else {
            toml = importLegacy(runDir);
        }

        String eng = toml.getString(SECTION, "defaultEngine", c.engine).trim();
        String norm = ClientVoiceConfig.normalize(eng); // accepts pre-rename ids too
        if (norm != null) c.engine = norm;
        else if (eng.equals("noop")) c.engine = eng;
        c.autoDownload = toml.getBool(SECTION, "autoDownload", c.autoDownload);
        c.maxFramesPerSecond = (int) toml.getInt(SECTION, "maxFramesPerSecond", c.maxFramesPerSecond);
        c.allowedEngines = toml.getStringList("engines", "allowed", c.allowedEngines);
        // One-time migrations: unedited default whitelists gain newer engines;
        // deliberately customized whitelists are never added to. All migrations
        // cascade (a pre-vosk-en-us file ends at the current default in one load).
        if (c.allowedEngines.equals(LEGACY_DEFAULT_ENGINES)) {
            c.allowedEngines = LEGACY_DEFAULT_PRE_CJK;
        }
        if (c.allowedEngines.equals(LEGACY_DEFAULT_PRE_CJK)
                || c.allowedEngines.equals(LEGACY_DEFAULT_PRE_RENAME)) {
            c.allowedEngines = DEFAULT_ALLOWED_ENGINES;
        }
        // Ids were renamed (vosk-en-us -> vosk-en, vosk-zh-cn -> vosk-cn, ...):
        // normalize every entry so whitelists written before the rename keep
        // working. Unknown/custom ids pass through unchanged.
        c.allowedEngines = c.allowedEngines.stream()
                .map(id -> {
                    String n = ClientVoiceConfig.normalize(id);
                    return n != null ? n : id;
                })
                .distinct()
                .toList();
        c.enabled = toml.getBool(SECTION, "enabled", c.enabled);
        c.whitelist = toml.getStringList("players", "whitelist", c.whitelist);

        c.save(runDir); // persist defaults + comments/structure
        return c;
    }

    /** One-time import of the old server.properties; returns a Toml pre-filled from it. */
    private static Toml importLegacy(Path runDir) {
        Toml toml = new Toml();
        Path legacy = runDir.resolve("config/voicecast").resolve("server.properties");
        if (Files.isRegularFile(legacy)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(legacy)) { p.load(in); }
            catch (IOException e) { VoiceCast.LOGGER.warn("Failed to read legacy {}", legacy, e); }
            if (p.getProperty("engine") != null) toml.setString(SECTION, "defaultEngine", p.getProperty("engine"));
            if (p.getProperty("autoDownload") != null)
                toml.setBool(SECTION, "autoDownload", Boolean.parseBoolean(p.getProperty("autoDownload")));
            VoiceCast.LOGGER.info("Imported legacy server.properties; migrating to voicecast.toml");
        }
        return toml;
    }

    public void save(Path runDir) {
        Toml toml = Toml.load(tomlFile(runDir)); // preserve client/other sections
        toml.setComment("VoiceCast configuration (client + server).")
            .setComment("Schema version " + SCHEMA_VERSION + ". Edit values, then restart or reload.")
            .setInt("", "version", SCHEMA_VERSION);
        toml.setString(SECTION, "defaultEngine", engine)
            .setBool(SECTION, "autoDownload", autoDownload)
            .setInt(SECTION, "maxFramesPerSecond", maxFramesPerSecond)
            .setBool(SECTION, "enabled", enabled);
        toml.setStringList("engines", "allowed", allowedEngines);
        toml.setStringList("players", "whitelist", whitelist);
        toml.save(tomlFile(runDir));
        // Legacy file is superseded.
        try {
            Files.deleteIfExists(runDir.resolve("config/voicecast").resolve("server.properties"));
        } catch (IOException ignored) {}
    }

    /** Whether the server is allowed to load/run the given engine. */
    public boolean engineAllowed(String engineId) {
        return allowedEngines.contains(engineId);
    }

    /** Parsed {@code [players] whitelist}; invalid UUID entries are skipped with a warning. */
    public java.util.Set<java.util.UUID> parsedWhitelist() {
        java.util.Set<java.util.UUID> out = new java.util.HashSet<>();
        for (String raw : whitelist) {
            try {
                out.add(java.util.UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException e) {
                VoiceCast.LOGGER.warn("Ignoring invalid UUID in [players].whitelist: '{}'", raw);
            }
        }
        return out;
    }
}
