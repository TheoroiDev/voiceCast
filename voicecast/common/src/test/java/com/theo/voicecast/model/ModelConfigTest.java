package com.theo.voicecast.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the model catalog. The first case reproduces a real
 * production failure: {@code defaultRoot()} used to attach model entries to a
 * detached map ({@code Json.getMap} returns a throwaway map for missing keys),
 * so the written models.json had no {@code models} section and every engine
 * binding resolved to null -> "No model configured for engine vosk-text".
 */
class ModelConfigTest {

    @TempDir
    Path runDir;

    @Test
    void allBuiltinEnginesResolve() {
        ModelConfig cfg = ModelConfig.load(runDir);
        assertModel(cfg.modelForEngine("vosk-text"), ModelConfig.MODEL_VOSK_EN);
        assertModel(cfg.modelForEngine("vosk-en-us"), ModelConfig.MODEL_VOSK_EN);
        assertModel(cfg.modelForEngine("vosk-zh-cn"), ModelConfig.MODEL_VOSK_ZH);
        assertModel(cfg.modelForEngine("vosk-ja-jp"), ModelConfig.MODEL_VOSK_JA);
        assertModel(cfg.modelForEngine("vosk-ko-kr"), ModelConfig.MODEL_VOSK_KO);
        assertModel(cfg.modelForEngine("ipa-phonemes"), ModelConfig.MODEL_IPA);
    }

    @Test
    void savedFileContainsModelsSection() throws Exception {
        ModelConfig.load(runDir);
        String json = Files.readString(runDir.resolve("config/voicecast/models.json"));
        assertTrue(json.contains("\"models\""), "models section missing from saved file");
        assertTrue(json.contains(ModelConfig.MODEL_VOSK_EN), "en model missing from saved file");
        assertTrue(json.contains(ModelConfig.MODEL_IPA), "ipa model missing from saved file");
    }

    /** A user file with the models section lost must still resolve via defaults. */
    @Test
    void missingModelsSectionFallsBackToDefaults() throws Exception {
        Path file = runDir.resolve("config/voicecast/models.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":1,\"engines\":{\"vosk-text\":{\"model\":\""
                + ModelConfig.MODEL_VOSK_EN + "\"}}}");
        ModelConfig cfg = ModelConfig.load(runDir);
        assertModel(cfg.modelForEngine("vosk-text"), ModelConfig.MODEL_VOSK_EN);
        assertModel(cfg.modelForEngine("vosk-en-us"), ModelConfig.MODEL_VOSK_EN);
        assertModel(cfg.modelForEngine("ipa-phonemes"), ModelConfig.MODEL_IPA);
    }

    @Test
    void userOverridesSurviveReload() throws Exception {
        Path file = runDir.resolve("config/voicecast/models.json");
        Files.createDirectories(file.getParent());
        String mirror = "https://example.com/vosk-model-small-en-us-0.15.zip";
        Files.writeString(file, "{\"version\":1,\"models\":{\"" + ModelConfig.MODEL_VOSK_EN
                + "\":{\"kind\":\"vosk-archive\",\"sizeBytes\":1,\"urls\":[\"" + mirror + "\"]}}}");
        ModelConfig cfg = ModelConfig.load(runDir);
        ModelConfig.ModelEntry en = cfg.modelForEngine("vosk-text");
        assertModel(en, ModelConfig.MODEL_VOSK_EN);
        assertEquals(List.of(mirror), en.urls());
    }

    private static void assertModel(ModelConfig.ModelEntry entry, String expectedModelId) {
        assertNotNull(entry, "model entry must resolve for " + expectedModelId);
        assertEquals(expectedModelId, entry.id());
        assertNotNull(entry.kind());
        if (ModelConfig.KIND_VOSK_ARCHIVE.equals(entry.kind())) {
            assertTrue(!entry.urls().isEmpty(), "vosk archive must have urls");
        }
    }
}
