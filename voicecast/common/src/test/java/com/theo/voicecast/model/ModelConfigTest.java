package com.theo.voicecast.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * models.json v2 catalog: one model = one engine; selection by model name or
 * language code (first declared wins); v1/foreign files are rewritten with
 * defaults (v0 policy — no migrations). Engine-swap C1 default lineup:
 * qwen3-asr-0.6b-int8 → zipa-ipa → gtcrn-simple-denoiser.
 */
class ModelConfigTest {

    @TempDir
    Path runDir;

    private Path file() {
        return runDir.resolve("config/voicecast/models.json");
    }

    // ---- defaults ----------------------------------------------------------

    @Test
    void defaultCatalogIsV2AndSelectable() {
        ModelConfig cfg = ModelConfig.load(runDir);
        assertEquals(List.of("qwen3-asr-0.6b-int8", "zipa-ipa"), cfg.engineIds());
        assertEquals(List.of("qwen3-asr-0.6b-int8", "zipa-ipa", "gtcrn-simple-denoiser"),
                cfg.modelIds());
        assertEquals("offline", cfg.model("qwen3-asr-0.6b-int8").type());
        assertEquals(List.of("en", "zh", "ja", "ko", "yue", "de", "fr", "es", "ru"),
                cfg.languagesFor("qwen3-asr-0.6b-int8"));
        assertEquals("sherpa-qwen3", cfg.familyFor("qwen3-asr-0.6b-int8"));
        assertEquals("encoder.int8.onnx", cfg.optionsFor("qwen3-asr-0.6b-int8").get("encoder"));
        assertEquals("tokenizer", cfg.optionsFor("qwen3-asr-0.6b-int8").get("tokenizer"));
        assertEquals("ipa", cfg.familyFor("zipa-ipa"));
        assertEquals("ipa", cfg.model("zipa-ipa").type());
        // integrity: archive size gate + pinned zipa weights checksum
        assertTrue(cfg.model("qwen3-asr-0.6b-int8").sizeBytes() > 800_000_000L);
        assertTrue(cfg.model("zipa-ipa").files().stream()
                .anyMatch(f -> "model.int8.onnx".equals(f.name()) && f.sha256() != null));
    }

    @Test
    void denoiserModelIsAuxiliary() {
        ModelConfig cfg = ModelConfig.load(runDir);
        ModelConfig.ModelEntry denoiser = cfg.denoiserModel();
        assertNotNull(denoiser, "default catalog must carry the gtcrn denoiser");
        assertEquals("denoiser", denoiser.type());
        assertNull(cfg.resolveModel("gtcrn-simple-denoiser"),
                "denoiser models must not be selectable as engines");
        assertEquals("qwen3-asr-0.6b-int8", cfg.resolveModel("en").id(),
                "language resolution must skip the denoiser entry");
        assertNull(cfg.familyFor("gtcrn-simple-denoiser"));
        // it still resolves through the model download pipeline
        assertNotNull(cfg.model("gtcrn-simple-denoiser"));
        assertTrue(cfg.model("gtcrn-simple-denoiser").files().toString().contains("gtcrn_simple.onnx"));
    }

    @Test
    void savedFileIsSelfSufficientV2() throws Exception {
        ModelConfig.load(runDir);
        String json = Files.readString(file());
        assertTrue(json.contains("\"version\": 2") || json.contains("\"version\":2"), "schema version 2 expected");
        assertTrue(json.contains("$schema"), "$schema pointer missing");
        assertTrue(json.contains("voicecast-models-v2.schema.json"), "$schema must point at the v2 schema");
        assertTrue(json.contains("\"properties\""), "v2 properties section missing");
        assertTrue(json.contains("\"source\""), "v2 source section missing");
        assertTrue(!json.contains("\"engines\""), "v2 has no engines section");
        // round trip: loading the saved file again yields the same catalog
        long before = ModelConfig.load(runDir).modelIds().size();
        assertTrue(before >= 3);
    }

    @Test
    void legacyV1FileIsRewrittenWithDefaults() throws Exception {
        Files.createDirectories(file().getParent());
        // v1 shape: flat kind/urls + separate engines section -> not v2, reset.
        Files.writeString(file(), "{\"version\":1,\"models\":{\"m\":{\"kind\":\"sherpa-archive\","
                + "\"urls\":[\"https://example.com/a.tar.bz2\"]}},\"engines\":{\"e\":{\"model\":\"m\"}}}");
        ModelConfig cfg = ModelConfig.load(runDir);
        assertNull(cfg.model("m"), "v1 entries must not survive (v0 hard switch)");
        assertEquals(List.of("qwen3-asr-0.6b-int8", "zipa-ipa", "gtcrn-simple-denoiser"),
                cfg.modelIds());
        assertEquals(List.of("qwen3-asr-0.6b-int8", "zipa-ipa"), cfg.engineIds());
    }

    // ---- v2 parsing --------------------------------------------------------

    @Test
    void userV2CatalogIsAuthoritative() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"version": 2, "models": {
                  "my-small": {"properties": {"lang": ["zh", "en"], "type": "stream", "tokens": "tokens.txt"},
                               "source": {"kind": "sherpa-archive", "urls": ["https://example.com/small.tar.bz2"]}},
                  "my-big": {"properties": {"lang": ["zh", "en"], "type": "stream"},
                             "source": {"kind": "sherpa-archive", "urls": ["https://example.com/big.tar.bz2"]}},
                  "my-sv": {"properties": {"lang": ["ja"], "type": "offline", "family": "my-addon-family"},
                            "source": {"kind": "sherpa-archive", "urls": ["https://example.com/sv.tar.bz2"]}}
                }}""");
        ModelConfig cfg = ModelConfig.load(runDir);
        assertEquals(List.of("my-small", "my-big", "my-sv"), cfg.modelIds());
        // declaration order drives the per-language default (first declared wins)
        assertEquals("my-small", cfg.resolveModel("zh").id());
        assertEquals("my-small", cfg.resolveModel("en").id());
        assertEquals("my-sv", cfg.resolveModel("ja").id());
        // builtin derivations cover only the ipa kind; every other family must
        // be declared explicitly (and registered by its owner addon)
        assertNull(cfg.familyFor("my-small"));
        assertEquals("my-addon-family", cfg.familyFor("my-sv"));
    }

    @Test
    void resolveModelPrecedenceNameThenLanguage() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"version": 2, "models": {
                  "en-model": {"properties": {"lang": ["en"], "type": "offline"},
                               "source": {"kind": "sherpa-archive", "urls": ["https://example.com/en.tar.bz2"]}},
                  "zh-model": {"properties": {"lang": ["zh"], "type": "offline"},
                               "source": {"kind": "sherpa-archive", "urls": ["https://example.com/zh.tar.bz2"]}}
                }}""");
        ModelConfig cfg = ModelConfig.load(runDir);
        // exact model name beats the language-code route
        assertEquals("zh-model", cfg.resolveModel("zh-model").id());
        assertEquals("en-model", cfg.resolveModel("en").id());
        // language names map through langCode
        assertEquals("zh-model", cfg.resolveModel("中文").id());
        assertEquals("en-model", cfg.resolveModel("English").id());
        assertNull(cfg.resolveModel("klingon"));
        assertNull(cfg.resolveModel(""));
        assertNull(cfg.resolveModel(null));
    }

    @Test
    void explicitFamilyOverridesDerivation() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"version": 2, "models": {
                  "custom": {"properties": {"lang": ["zh"], "type": "offline", "family": "my-family"},
                             "source": {"kind": "sherpa-archive", "urls": ["https://example.com/c.tar.bz2"]}}
                }}""");
        ModelConfig cfg = ModelConfig.load(runDir);
        assertEquals("my-family", cfg.familyFor("custom"));
    }

    // ---- zipa defaults -----------------------------------------------------

    @Test
    void zipaEntryPinsWeightsAndTokens() {
        ModelConfig cfg = ModelConfig.load(runDir);
        ModelConfig.ModelEntry zipa = cfg.model("zipa-ipa");
        assertNotNull(zipa);
        assertTrue(zipa.files().stream().anyMatch(f -> f.name().equals(ZipaModel.MODEL_FILE)),
                "int8 weights must stay configured");
        assertTrue(zipa.files().stream().anyMatch(f -> f.name().equals(ZipaModel.TOKENS_FILE)),
                "tokens must stay configured");
        ModelConfig.FileEntry weights = zipa.files().stream()
                .filter(f -> f.name().equals(ZipaModel.MODEL_FILE)).findFirst().orElseThrow();
        assertTrue(weights.minBytes() >= 60L * 1024 * 1024, "weights size gate");
        assertTrue(weights.sha256() != null && weights.sha256().length() == 64, "pinned weights checksum");
        assertTrue(weights.urls().stream().anyMatch(u -> u.contains("huggingface.co/anyspeech/zipa")));
        assertTrue(weights.urls().stream().anyMatch(u -> u.contains("hf-mirror.com/anyspeech/zipa")));
    }

    @Test
    void shippedModelsCarryLicenseMetadata() {
        ModelConfig cfg = ModelConfig.load(runDir);
        for (String id : cfg.modelIds()) {
            var entry = cfg.model(id);
            org.junit.jupiter.api.Assertions.assertNotNull(entry.license(),
                    "shipped model without license metadata: " + id);
            org.junit.jupiter.api.Assertions.assertFalse(entry.license().name().isBlank(), id);
            org.junit.jupiter.api.Assertions.assertFalse(entry.license().url().isBlank(), id);
        }
        org.junit.jupiter.api.Assertions.assertEquals("Apache-2.0",
                cfg.model("qwen3-asr-0.6b-int8").license().name());
        org.junit.jupiter.api.Assertions.assertEquals("MIT",
                cfg.model("zipa-ipa").license().name());
    }
}
