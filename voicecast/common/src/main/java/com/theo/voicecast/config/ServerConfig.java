package com.theo.voicecast.config;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Server-side VoiceCast configuration, stored in
 * {@code config/voicecast/voicecast.toml} (shared file; server reads the
 * {@code [server]} / {@code [engines]} sections). Decides which recognizer is
 * warmed by default and whether models auto-download.
 *
 * <p>Schema is versioned ({@code version} key); missing keys are filled with
 * defaults and the file is re-written. No legacy imports or migrations (v0
 * policy, AGENTS §3).
 *
 * <p>Engine ids are model names from models.json (v2 catalog: one model = one
 * engine). {@code defaultEngine} may also be a two-letter language code
 * (resolved to the first declared model supporting it); empty/unknown values
 * resolve to the catalog default at use time. {@code [engines] allowed} empty
 * means every catalog model is allowed.
 */
public final class ServerConfig {
    public static final String SECTION = "server";
    public static final int SCHEMA_VERSION = 1;

    /** Default engine: model name or language code; empty = catalog default (first declared zh model). */
    public String engine = "";
    public boolean autoDownload = true;
    public int maxFramesPerSecond = 15;
    /** Allowed engine ids; empty = all models declared in models.json. */
    public List<String> allowedEngines = List.of();
    /** Master switch: when false, no player may stream audio (models stay unloaded). */
    public boolean enabled = true;
    /** {@code [players] whitelist} of raw UUID strings; empty = everyone. */
    public List<String> whitelist = List.of();

    private ServerConfig() {}

    private static Path tomlFile(Path runDir) {
        return runDir.resolve("config/voicecast").resolve("voicecast.toml");
    }

    public static ServerConfig load(Path runDir) {
        ServerConfig c = new ServerConfig();
        Path f = tomlFile(runDir);
        if (Files.isRegularFile(f)) {
            Toml toml = Toml.load(f);
            c.engine = toml.getString(SECTION, "defaultEngine", c.engine).trim().toLowerCase(java.util.Locale.ROOT);
            c.autoDownload = toml.getBool(SECTION, "autoDownload", c.autoDownload);
            c.maxFramesPerSecond = (int) toml.getInt(SECTION, "maxFramesPerSecond", c.maxFramesPerSecond);
            c.allowedEngines = toml.getStringList("engines", "allowed", c.allowedEngines).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            c.enabled = toml.getBool(SECTION, "enabled", c.enabled);
            c.whitelist = toml.getStringList("players", "whitelist", c.whitelist);
        }

        c.save(runDir); // persist defaults + comments/structure
        return c;
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
    }

    /** Whether the server is allowed to load/run the given engine.
     *  An empty {@code [engines] allowed} list means every catalog model. */
    public boolean engineAllowed(String engineId) {
        return allowedEngines.isEmpty() || allowedEngines.contains(engineId);
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
