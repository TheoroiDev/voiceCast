package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Client prefs: engine value is stored verbatim (lowercased); empty = catalog default. */
class ClientVoiceConfigTest {

    @TempDir
    Path runDir;

    @Test
    void freshInstallHasEmptyEngine() {
        ClientVoiceConfig c = ClientVoiceConfig.load(runDir);
        assertEquals("", c.engine, "empty = resolve against the catalog at use time");
    }

    @Test
    void engineRoundTripsLowercased() {
        ClientVoiceConfig c = new ClientVoiceConfig();
        c.engine = "  My-Small  ";
        c.save(runDir);
        assertEquals("my-small", ClientVoiceConfig.load(runDir).engine);
    }
}
