package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server config loading: {@code [engines].allowed} migrations and engine id
 * validation. Stale default whitelists must upgrade to the current builtin set
 * (cascade: a pre-vosk-en-us file lands at the current default in one load),
 * while deliberately customized whitelists are left untouched (but legacy ids
 * inside them are normalized to the renamed ids). CJK engine ids are valid
 * values for {@code [server] defaultEngine}.
 */
class ServerConfigTest {

    @TempDir
    Path runDir;

    private static Path tomlFile(Path runDir) {
        return runDir.resolve("config/voicecast/voicecast.toml");
    }

    private static void seed(Path runDir, String section, String key, List<String> values) {
        Toml toml = Toml.load(tomlFile(runDir));
        toml.setStringList(section, key, values);
        toml.save(tomlFile(runDir));
    }

    private static void seedDefaultEngine(Path runDir, String engine) {
        Toml toml = Toml.load(tomlFile(runDir));
        toml.setString("server", "defaultEngine", engine);
        toml.save(tomlFile(runDir));
    }

    @Test
    void freshInstallGetsFullWhitelist() {
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(ServerConfig.DEFAULT_ALLOWED_ENGINES, c.allowedEngines);
        assertTrue(c.allowedEngines.containsAll(java.util.List.of(
                "vosk-en", "vosk-cn", "vosk-jp", "vosk-kr")));
    }

    @Test
    void preCjkDefaultWhitelistIsUpgraded() {
        seed(runDir, "engines", "allowed", List.of("vosk-text", "vosk-en-us", "ipa-phonemes"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(ServerConfig.DEFAULT_ALLOWED_ENGINES, c.allowedEngines);
    }

    @Test
    void preRenameDefaultWhitelistIsUpgraded() {
        seed(runDir, "engines", "allowed",
                List.of("vosk-text", "vosk-en-us", "vosk-zh-cn", "vosk-ja-jp", "vosk-ko-kr", "ipa-phonemes"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(ServerConfig.DEFAULT_ALLOWED_ENGINES, c.allowedEngines);
    }

    @Test
    void preVoskEnUsDefaultWhitelistCascadesToCurrentDefault() {
        seed(runDir, "engines", "allowed", List.of("vosk-text", "ipa-phonemes"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(ServerConfig.DEFAULT_ALLOWED_ENGINES, c.allowedEngines);
    }

    @Test
    void customizedWhitelistIsLeftAlone() {
        List<String> custom = List.of("vosk-text", "ipa-phonemes", "my-custom-engine");
        seed(runDir, "engines", "allowed", custom);
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(custom, c.allowedEngines);
    }

    /** Legacy ids inside a customized whitelist migrate to the renamed ids. */
    @Test
    void customizedWhitelistLegacyIdsAreNormalized() {
        seed(runDir, "engines", "allowed", List.of("vosk-en-us", "vosk-zh-cn", "ipa-phonemes"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(List.of("vosk-en", "vosk-cn", "ipa-phonemes"), c.allowedEngines);
        assertTrue(c.engineAllowed("vosk-en"));
        assertTrue(c.engineAllowed("vosk-cn"));
    }

    @Test
    void cjkDefaultEngineIsAccepted() {
        seedDefaultEngine(runDir, "vosk-cn");
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals("vosk-cn", c.engine);
    }

    /** A pre-rename defaultEngine value migrates to the renamed id. */
    @Test
    void legacyDefaultEngineIsNormalized() {
        seedDefaultEngine(runDir, "vosk-zh-cn");
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals("vosk-cn", c.engine);
    }

    @Test
    void unknownDefaultEngineFallsBackToVoskText() {
        seedDefaultEngine(runDir, "vosk-ru-ru");
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals("vosk-text", c.engine);
    }
}
