package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server config loading, v0 semantics: no migrations (AGENTS §3). Engine ids
 * are models.json v2 model names; an empty {@code [engines] allowed} list
 * means every catalog model is allowed; unknown entries are kept verbatim
 * (they simply never match a catalog model).
 */
class ServerConfigTest {

    @TempDir
    Path runDir;

    private static Path tomlFile(Path runDir) {
        return runDir.resolve("config/voicecast/voicecast.toml");
    }

    private static void seedAllowed(Path runDir, List<String> values) {
        Toml toml = Toml.load(tomlFile(runDir));
        toml.setStringList("engines", "allowed", values);
        toml.save(tomlFile(runDir));
    }

    @Test
    void freshInstallAllowsEverythingViaEmptyList() {
        ServerConfig c = ServerConfig.load(runDir);
        assertTrue(c.allowedEngines.isEmpty(), "default whitelist is empty (= all catalog models)");
        assertTrue(c.engineAllowed("any-model-name"));
        assertTrue(c.engineAllowed("qwen3-asr-0.6b-int8"));
    }

    @Test
    void customAllowlistIsExact() {
        seedAllowed(runDir, List.of("my-small", "qwen3-asr-0.6b-int8"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(List.of("my-small", "qwen3-asr-0.6b-int8"), c.allowedEngines);
        assertTrue(c.engineAllowed("my-small"));
        assertFalse(c.engineAllowed("my-other"), "non-listed engines are refused");
    }

    @Test
    void unknownIdsPassThroughUnchanged() {
        seedAllowed(runDir, List.of("vosk-en", "whatever-id"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(List.of("vosk-en", "whatever-id"), c.allowedEngines,
                "v0: entries are kept verbatim, no normalization");
    }

    @Test
    void defaultEngineKeptVerbatimAndBlankBecomesEmpty() {
        Toml toml = Toml.load(tomlFile(runDir));
        toml.setString("server", "defaultEngine", "  My-Small  ");
        toml.save(tomlFile(runDir));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals("my-small", c.engine, "trimmed + lowercased, resolution happens against the catalog");

        Toml t2 = Toml.load(tomlFile(runDir));
        t2.setString("server", "defaultEngine", "   ");
        t2.save(tomlFile(runDir));
        c = ServerConfig.load(runDir);
        assertEquals("", c.engine, "blank defaultEngine = catalog default");
    }
}
