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
 * defaults (v0 policy — no migrations).
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
        assertEquals(List.of(
                "sherpa-zipformer-bilingual-zh-en-int8",
                "sherpa-sensevoice-small-int8",
                "sherpa-sensevoice-full",
                "wav2vec2-espeak-ipa"), cfg.engineIds());
        assertEquals(List.of(
                "sherpa-zipformer-bilingual-zh-en-int8",
                "sherpa-sensevoice-small-int8",
                "sherpa-sensevoice-full",
                "wav2vec2-espeak-ipa",
                "gtcrn-simple-denoiser"), cfg.modelIds());
        assertEquals("stream", cfg.model("sherpa-zipformer-bilingual-zh-en-int8").type());
        assertEquals(List.of("zh", "en"), cfg.languagesFor("sherpa-zipformer-bilingual-zh-en-int8"));
        assertEquals("sherpa-streaming", cfg.familyFor("sherpa-zipformer-bilingual-zh-en-int8"));
        assertEquals("sherpa-sensevoice", cfg.familyFor("sherpa-sensevoice-small-int8"));
        assertEquals("sherpa-sensevoice", cfg.familyFor("sherpa-sensevoice-full"));
        assertEquals("ipa", cfg.familyFor("wav2vec2-espeak-ipa"));
        assertEquals("cjkchar+bpe", cfg.optionsFor("sherpa-zipformer-bilingual-zh-en-int8").get("modeling_unit"));
        // fp32 A/B entry: points at the float weights inside the full archive
        assertEquals("model.onnx", cfg.optionsFor("sherpa-sensevoice-full").get("onnx"));
    }

    @Test
    void denoiserModelIsAuxiliary() {
        ModelConfig cfg = ModelConfig.load(runDir);
        ModelConfig.ModelEntry denoiser = cfg.denoiserModel();
        assertNotNull(denoiser, "default catalog must carry the gtcrn denoiser");
        assertEquals("denoiser", denoiser.type());
        assertNull(cfg.resolveModel("gtcrn-simple-denoiser"),
                "denoiser models must not be selectable as engines");
        assertEquals("sherpa-zipformer-bilingual-zh-en-int8", cfg.resolveModel("en").id(),
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
        assertTrue(before >= 4);
    }

    @Test
    void legacyV1FileIsRewrittenWithDefaults() throws Exception {
        Files.createDirectories(file().getParent());
        // v1 shape: flat kind/urls + separate engines section -> not v2, reset.
        Files.writeString(file(), "{\"version\":1,\"models\":{\"m\":{\"kind\":\"sherpa-archive\","
                + "\"urls\":[\"https://example.com/a.tar.bz2\"]}},\"engines\":{\"e\":{\"model\":\"m\"}}}");
        ModelConfig cfg = ModelConfig.load(runDir);
        assertNull(cfg.model("m"), "v1 entries must not survive (v0 hard switch)");
        assertEquals(List.of(
                "sherpa-zipformer-bilingual-zh-en-int8",
                "sherpa-sensevoice-small-int8",
                "sherpa-sensevoice-full",
                "wav2vec2-espeak-ipa",
                "gtcrn-simple-denoiser"), cfg.modelIds());
        assertEquals(List.of(
                "sherpa-zipformer-bilingual-zh-en-int8",
                "sherpa-sensevoice-small-int8",
                "sherpa-sensevoice-full",
                "wav2vec2-espeak-ipa"), cfg.engineIds());
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
                  "my-sv": {"properties": {"lang": ["ja"], "type": "offline"},
                            "source": {"kind": "sherpa-archive", "urls": ["https://example.com/sv.tar.bz2"]}}
                }}""");
        ModelConfig cfg = ModelConfig.load(runDir);
        assertEquals(List.of("my-small", "my-big", "my-sv"), cfg.modelIds());
        // declaration order drives the per-language default (first declared wins)
        assertEquals("my-small", cfg.resolveModel("zh").id());
        assertEquals("my-small", cfg.resolveModel("en").id());
        assertEquals("my-sv", cfg.resolveModel("ja").id());
        // explicit family override + derived family
        assertEquals("sherpa-streaming", cfg.familyFor("my-small"));
        assertEquals("sherpa-sensevoice", cfg.familyFor("my-sv"));
    }

    @Test
    void resolveModelPrecedenceNameThenLanguage() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"version": 2, "models": {
                  "en-model": {"properties": {"lang": ["en"], "type": "stream"},
                               "source": {"kind": "sherpa-archive", "urls": ["https://example.com/en.tar.bz2"]}},
                  "zh-model": {"properties": {"lang": ["zh"], "type": "stream"},
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
                  "custom": {"properties": {"lang": ["zh"], "type": "stream", "family": "my-family"},
                             "source": {"kind": "sherpa-archive", "urls": ["https://example.com/c.tar.bz2"]}}
                }}""");
        ModelConfig cfg = ModelConfig.load(runDir);
        assertEquals("my-family", cfg.familyFor("custom"));
    }

    // ---- ipa defaults ------------------------------------------------------

    @Test
    void ipaEntryHasNoFloat32Fallback() {
        ModelConfig cfg = ModelConfig.load(runDir);
        ModelConfig.ModelEntry ipa = cfg.model("wav2vec2-espeak-ipa");
        assertNotNull(ipa);
        assertTrue(ipa.files().stream().noneMatch(f -> f.name().equals("model.onnx")),
                "float32 model.onnx fallback must not be configured");
        assertTrue(ipa.files().stream().anyMatch(f -> f.name().equals(IpaModel.Q4_FILE)),
                "q4 weights must stay configured");
        assertTrue(ipa.files().stream().anyMatch(f -> f.name().equals(IpaModel.VOCAB_FILE)),
                "vocab must stay configured");
    }
}
