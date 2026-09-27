package com.theo.voicecast.server;

import com.theo.voicecast.model.ModelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * voiceCast#51 refine (2026-09-27): the consent/autoDownload decision core
 * was previously enforced only inline in loadEngine, so the two resolve-path
 * bypasses (createRecognizer / configure) shipped with zero test coverage.
 * The decision now lives in one pure function — pin all four quadrants.
 */
class VoiceCastServerLicenseGateTest {

    private static ModelConfig.ModelEntry entry(String id) {
        return new ModelConfig.ModelEntry(id, ModelConfig.KIND_SHERPA_ARCHIVE, 1024, null,
                List.of("https://example.invalid/m.tar.bz2"), List.of(),
                "offline", List.of("en"), java.util.Map.of(),
                new ModelConfig.LicenseInfo("MIT", "https://example.invalid/LICENSE"));
    }

    @Test
    void absentModelWithoutAcceptanceIsBlockedWithPointer() {
        String msg = VoiceCastServer.licenseGateMessage(entry("m"), false, true, false);
        assertNotNull(msg);
        assertTrue(msg.contains("license not accepted"), msg);
        assertTrue(msg.contains("/voicecast licenses accept"), msg);
    }

    @Test
    void absentAcceptedModelWithAutoDownloadPasses() {
        assertNull(VoiceCastServer.licenseGateMessage(entry("m"), true, true, false));
    }

    @Test
    void absentAcceptedModelWithAutoDownloadOffIsBlockedWithPointer() {
        String msg = VoiceCastServer.licenseGateMessage(entry("m"), true, false, false);
        assertNotNull(msg);
        assertTrue(msg.contains("autoDownload=false"), msg);
    }

    @Test
    void presentModelNeverGates() {
        assertNull(VoiceCastServer.licenseGateMessage(entry("m"), false, true, true));
        assertNull(VoiceCastServer.licenseGateMessage(entry("m"), false, false, true));
    }

    @Test
    void nullEntryAndMissingLicenseNameAreHandled() {
        assertNull(VoiceCastServer.licenseGateMessage(null, false, false, false));
        ModelConfig.ModelEntry noLicense = new ModelConfig.ModelEntry("bare",
                ModelConfig.KIND_SHERPA_ARCHIVE, 1, null, List.of(), List.of(),
                "offline", List.of(), java.util.Map.of(), null);
        String msg = VoiceCastServer.licenseGateMessage(noLicense, false, true, false);
        assertNotNull(msg);
        assertTrue(msg.contains("unspecified"), msg);
    }
}
