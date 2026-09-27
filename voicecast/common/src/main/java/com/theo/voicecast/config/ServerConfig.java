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

    // [modelLicenses] — voiceCast#51: explicit acceptance of each downloadable
    // model's license terms. The downloader REFUSES to fetch a model whose id
    // is absent from this list; `/voicecast licenses accept` (ops) writes it.
    public List<String> acceptedLicenses = List.of();

    // [match] — engine-calibration defaults for the semantic adjudication
    // (semantic contract v2, C1b §0.3: the threshold/margin brain moved from
    // WizardReal constants into voicecast config).
    /** CTC posterior acceptance threshold (the pre-v2 WizardReal
     *  FORWARD_MATCH_THRESHOLD 0.10 calibration). */
    public float matchForwardThreshold = 0.10f;
    /** Phoneme-similarity threshold (weighted edit distance tier). */
    public float matchPhonemeThreshold = 0.6f;
    /** Text-similarity threshold (alias matching tier). */
    public float matchTextThreshold = 0.65f;
    /** CTC top1-top2 margin (m1_retest calibration). */
    public float matchCtcMargin = 0.02f;

    /** The {@code [match]} calibration as an API record. */
    public com.theo.voicecast.api.Calibration calibration() {
        return new com.theo.voicecast.api.Calibration(matchForwardThreshold, matchPhonemeThreshold,
                matchTextThreshold, matchCtcMargin);
    }

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
            c.acceptedLicenses = toml.getStringList("modelLicenses", "accepted", c.acceptedLicenses).stream()
                    .map(String::trim)
                    .filter(s2 -> !s2.isEmpty())
                    .distinct()
                    .toList();
            c.matchForwardThreshold = (float) toml.getDouble("match", "forwardThreshold", c.matchForwardThreshold);
            c.matchPhonemeThreshold = (float) toml.getDouble("match", "phonemeThreshold", c.matchPhonemeThreshold);
            c.matchTextThreshold = (float) toml.getDouble("match", "textThreshold", c.matchTextThreshold);
            c.matchCtcMargin = (float) toml.getDouble("match", "ctcMargin", c.matchCtcMargin);
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
        toml.setStringList("modelLicenses", "accepted", acceptedLicenses);
        toml.setDouble("match", "forwardThreshold", matchForwardThreshold)
            .setDouble("match", "phonemeThreshold", matchPhonemeThreshold)
            .setDouble("match", "textThreshold", matchTextThreshold)
            .setDouble("match", "ctcMargin", matchCtcMargin);
        toml.save(tomlFile(runDir));
    }

    /** Whether the server is allowed to load/run the given engine.
     *  An empty {@code [engines] allowed} list means every catalog model. */
    public boolean engineAllowed(String engineId) {
        return allowedEngines.isEmpty() || allowedEngines.contains(engineId);
    }

    /** voiceCast#51: whether the license terms of {@code modelId} have been
     *  explicitly accepted on this server. */
    public boolean licenseAccepted(String modelId) {
        return acceptedLicenses.contains(modelId);
    }

    /** Record an acceptance and persist it immediately. */
    public void acceptLicense(Path runDir, String modelId) {
        if (licenseAccepted(modelId)) return;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(acceptedLicenses);
        out.add(modelId);
        acceptedLicenses = List.copyOf(out);
        save(runDir);
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
