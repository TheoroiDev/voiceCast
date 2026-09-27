package com.theo.voicecast.config;

import com.theo.voicecast.VoiceCast;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Client-side VoiceCast preferences, stored in the shared
 * {@code config/voicecast/voicecast.toml} under the {@code [client]} section.
 *
 * <p>Client-only (the player's preferred recognizer engine). Contains no
 * GLFW/LWJGL references so the common jar stays safe on a dedicated server.
 *
 * <p>The engine value is a model name from models.json (v2 catalog: one model
 * = one engine), a two-letter language code, or {@code noop}. An empty value
 * means "catalog default" — the first declared model supporting the player's
 * language, resolved at use time against the catalog. There are no legacy
 * aliases or migrations (v0 policy, AGENTS §3); unknown saved values fall
 * back to the catalog default at resolution time.
 */
public final class ClientVoiceConfig {
    public static final String SECTION = "client";
    public static final String SECTION_COMPAT = "compat";
    /** Pseudo-engine: accept audio, never recognize. Always selectable. */
    public static final String ENGINE_NOOP = "noop";

    /** Preferred engine (model name / language code / noop); empty = catalog default. */
    public String engine = "";
    /**
     * Optional microphone noise suppression (GTCRN via sherpa-onnx) for the
     * recognition path only. Default off. What other players hear through
     * Simple Voice Chat is a separate capture — use SVC's own noise
     * suppression for that channel.
     */
    public boolean noiseSuppression = false;
    /** How to coexist with Simple Voice Chat when both mods want the microphone.
     * Only {@link SvcCoexistence#SHARE} exists; the former defer mode was removed
     * (see voicecast#27) — parsing {@code defer} falls back to SHARE with a warn. */
    public SvcCoexistence svcCoexistence = SvcCoexistence.SHARE;

    /** [client] acceptedLicenses (voiceCast#51): model ids whose license terms
     *  the player has accepted — the client-side denoiser download is gated on
     *  this list. Manage via /voicecast licenses accept. */
    public java.util.List<String> acceptedLicenses = java.util.List.of();

    /** voiceCast#51: whether the player accepted the license of {@code modelId}. */
    public boolean licenseAccepted(String modelId) {
        return acceptedLicenses.contains(modelId);
    }

    /** Record an acceptance and persist it immediately. */
    public void acceptLicense(Path runDir, String modelId) {
        if (licenseAccepted(modelId)) return;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(acceptedLicenses);
        out.add(modelId);
        acceptedLicenses = java.util.List.copyOf(out);
        save(runDir);
    }

    public enum SvcCoexistence {
        SHARE;
        static SvcCoexistence parse(String s) {
            if (s == null) return SHARE;
            if ("defer".equalsIgnoreCase(s)) {
                VoiceCast.LOGGER.warn("svcCoexistence 'defer' was removed (voicecast#27); using share");
                return SHARE;
            }
            return SHARE;
        }
    }

    private static Path tomlFile(Path runDir) {
        return runDir.resolve("config/voicecast").resolve("voicecast.toml");
    }

    public static ClientVoiceConfig load(Path runDir) {
        ClientVoiceConfig c = new ClientVoiceConfig();
        Path f = tomlFile(runDir);
        if (Files.isRegularFile(f)) {
            var toml = Toml.load(f);
            c.engine = toml.getString(SECTION, "engine", c.engine).trim().toLowerCase(Locale.ROOT);
            c.noiseSuppression = toml.getBool(SECTION, "noiseSuppression", c.noiseSuppression);
            c.svcCoexistence = SvcCoexistence.parse(toml.getString(SECTION_COMPAT, "svcCoexistence", "share"));
            c.acceptedLicenses = toml.getStringList(SECTION, "acceptedLicenses", c.acceptedLicenses).stream()
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .distinct()
                    .toList();
        }
        return c;
    }

    /** Persist only the {@code [client]}/{@code [compat]} sections, leaving server/other keys intact. */
    public void save(Path runDir) {
        Path f = tomlFile(runDir);
        Toml toml = Files.isRegularFile(f) ? Toml.load(f) : new Toml();
        toml.setInt("", "version", ServerConfig.SCHEMA_VERSION);
        toml.setString(SECTION, "engine", engine);
        toml.setBool(SECTION, "noiseSuppression", noiseSuppression);
        toml.setStringList(SECTION, "acceptedLicenses", acceptedLicenses);
        toml.setString(SECTION_COMPAT, "svcCoexistence", svcCoexistence.name().toLowerCase(Locale.ROOT));
        toml.save(f);
    }
}
