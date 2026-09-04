package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server config loading: {@code [engines].allowed} migrations and engine id
 * validation. All vosk-family legacy whitelists upgrade to the sherpa builtin
 * set in one cascade. Customized whitelists keep their custom ids but vosk
 * family ids inside them normalize to sherpa equivalents.
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
                "sherpa-zh-en", "sherpa-sensevoice", "ipa-phonemes")));
    }

    /** All pre-0.4.0 vosk-family default whitelists cascade to the sherpa set. */
    @Test
    void preSherpaDefaultWhitelistsAreUpgraded() {
        // 0.3.x ids
        seed(runDir, "engines", "allowed", List.of("vosk-en", "vosk-cn", "vosk-jp", "vosk-kr", "ipa-phonemes"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(ServerConfig.DEFAULT_ALLOWED_ENGINES, c.allowedEngines);

        // 0.4.0 intermediate (two-letter vosk codes)
        seed(runDir, "engines", "allowed", List.of("vosk-en", "vosk-zh", "vosk-ja", "vosk-ko", "ipa-phonemes"));
        c = ServerConfig.load(runDir);
        assertEquals(ServerConfig.DEFAULT_ALLOWED_ENGINES, c.allowedEngines);
    }

    @Test
    void voskTextDefaultWhitelistIsUpgraded() {
        seed(runDir, "engines", "allowed",
                List.of("vosk-text", "vosk-en", "vosk-cn", "vosk-jp", "vosk-kr", "ipa-phonemes"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(ServerConfig.DEFAULT_ALLOWED_ENGINES, c.allowedEngines);
    }

    @Test
    void customizedWhitelistKeepsCustomButNormalizesVosk() {
        seed(runDir, "engines", "allowed", List.of("vosk-text", "ipa-phonemes", "my-custom-engine"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(List.of("sherpa-zh-en", "ipa-phonemes", "my-custom-engine"), c.allowedEngines);
    }

    @Test
    void customizedWhitelistIsLeftAlone() {
        List<String> custom = List.of("ipa-phonemes", "my-custom-engine");
        seed(runDir, "engines", "allowed", custom);
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(custom, c.allowedEngines);
    }

    @Test
    void voskIdsInCustomWhitelistNormalizeToSherpa() {
        seed(runDir, "engines", "allowed", List.of("vosk-en-us", "vosk-zh-cn", "vosk-cn", "ipa-phonemes"));
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals(List.of("sherpa-zh-en", "ipa-phonemes"), c.allowedEngines);
        assertTrue(c.engineAllowed("sherpa-zh-en"));
    }

    @Test
    void voskDefaultEngineMigratesToSherpa() {
        seedDefaultEngine(runDir, "vosk-cn");
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals("sherpa-zh-en", c.engine);
        assertTrue(c.engineAllowed("sherpa-zh-en"));
    }

    @Test
    void legacyDefaultEngineIsNormalized() {
        seedDefaultEngine(runDir, "vosk-zh-cn");
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals("sherpa-zh-en", c.engine);
    }

    @Test
    void unknownDefaultEngineFallsBackToSherpa() {
        seedDefaultEngine(runDir, "vosk-ru-ru");
        ServerConfig c = ServerConfig.load(runDir);
        assertEquals("sherpa-zh-en", c.engine);
    }
}
