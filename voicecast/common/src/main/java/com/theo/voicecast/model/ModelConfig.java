package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven model catalog loaded from {@code config/voicecast/models.json}.
 *
 * <p>Replaces the hardcoded model URLs/SHA-256s in {@code VoskModel}/{@code IpaModel}:
 * every model's download URLs (mirrors), size, SHA-256 and per-file constraints are
 * configurable. When several URLs are listed, {@link ModelManager} probes them and
 * downloads from the fastest responding mirror. The file is auto-created with
 * defaults on first run and re-written with the full default schema afterwards, so
 * users can simply delete keys they want reset.
 *
 * <p>Schema (versioned via the {@code version} key):
 * <pre>
 * {
 *   "version": 1,
 *   "mirrorProbe": { "enabled": true, "probeBytes": 262144, "timeoutMs": 5000, "minFileSizeBytes": 8388608 },
 *   "models": {
 *     "vosk-model-small-en-us-0.15": { "kind": "vosk-archive", "sizeBytes": ..., "sha256": "...", "urls": [...] },
 *     "wav2vec2-espeak-ipa": { "kind": "loose-files", "files": [ { "name": ..., "urls": [...], "minBytes": ..., "optional": true } ] }
 *   },
 *   "engines": { "vosk-en": { "model": "vosk-model-small-en-us-0.15" }, ... }
 * }
 * </pre>
 */
public final class ModelConfig {
    public static final String FILE_NAME = "models.json";
    public static final int SCHEMA_VERSION = 1;

    public static final String KIND_SHERPA_ARCHIVE = "sherpa-archive";
    public static final String KIND_LOOSE_FILES = "loose-files";

    public static final String MODEL_SHERPA_ZH_EN = "sherpa-zipformer-bilingual-zh-en-int8";
    public static final String MODEL_SHERPA_SENSEVOICE = "sherpa-sensevoice-small-int8";
    public static final String MODEL_IPA = "wav2vec2-espeak-ipa";

    public record FileEntry(String name, List<String> urls, String sha256, long minBytes, boolean optional) {}

    public record ModelEntry(String id, String kind, long sizeBytes, String sha256,
                             List<String> urls, List<FileEntry> files) {}

    public record MirrorProbe(boolean enabled, long probeBytes, long timeoutMs, long minFileSizeBytes) {
        public static final MirrorProbe DEFAULT = new MirrorProbe(true, 262_144, 5_000, 8L * 1024 * 1024);
    }

    private final Map<String, ModelEntry> models = new LinkedHashMap<>();
    private final Map<String, String> engineModel = new LinkedHashMap<>();
    /** engines.<id>.type — engine family key (EngineFamilies lookup); inferred when absent. */
    private final Map<String, String> engineType = new LinkedHashMap<>();
    /** engines.<id>.language — two-letter code (legacy single-value form). */
    private final Map<String, String> engineLanguage = new LinkedHashMap<>();
    /** engines.<id>.languages — multi-bucket engines (bilingual/multilingual models). */
    private final Map<String, List<String>> engineLanguages = new LinkedHashMap<>();
    /** engines.<id>.options — raw per-engine option map passed to the family factory. */
    private final Map<String, Map<String, String>> engineOptions = new LinkedHashMap<>();
    private MirrorProbe probe = MirrorProbe.DEFAULT;
    private final Path file;

    private ModelConfig(Path file) { this.file = file; }

    public ModelEntry modelForEngine(String engineId) {
        String modelId = engineModel.get(engineId);
        return modelId == null ? null : models.get(modelId);
    }

    public String modelIdForEngine(String engineId) { return engineModel.get(engineId); }

    /** Language bucket served by an engine (two-letter code), or null when the
     * entry declares none (language-agnostic engines like ipa-phonemes). */
    public String languageForEngine(String engineId) { return engineLanguage.get(engineId); }

    /** All language buckets for an engine (two-letter codes), or empty list. */
    public List<String> languagesForEngine(String engineId) {
        return engineLanguages.getOrDefault(engineId, List.of());
    }

    /** Engine family type, or null when not declared (caller infers from kind). */
    public String typeForEngine(String engineId) { return engineType.get(engineId); }

    /** Raw per-engine option map (string→string), or empty. */
    public Map<String, String> optionsForEngine(String engineId) {
        return engineOptions.getOrDefault(engineId, Map.of());
    }

    public ModelEntry model(String modelId) { return models.get(modelId); }

    /** Kind for an engine's bound model, or null (unknown/unbound). */
    public String entryKind(String engineId) {
        ModelEntry e = modelForEngine(engineId);
        return e == null ? null : e.kind();
    }

    public MirrorProbe probe() { return probe; }

    public Path file() { return file; }

    // ------------------------------------------------------------------ load

    public static ModelConfig load(Path runDir) {
        Path dir = runDir.resolve("config/voicecast");
        Path file = dir.resolve(FILE_NAME);
        ModelConfig cfg = new ModelConfig(file);
        Map<String, Object> root = Map.of();
        if (Files.isRegularFile(file)) {
            try {
                root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                VoiceCast.LOGGER.error("Failed to parse {} ({}); rewriting with defaults", file, e.toString());
            }
        } else {
            VoiceCast.LOGGER.info("No {} found; creating with default model catalog", file);
        }
        cfg.read(root);
        cfg.save();
        return cfg;
    }

    private void read(Map<String, Object> root) {
        Map<String, Object> probeMap = Json.getMap(root, "mirrorProbe");
        if (!probeMap.isEmpty()) {
            probe = new MirrorProbe(
                    Json.getBool(probeMap, "enabled", MirrorProbe.DEFAULT.enabled()),
                    Math.max(4096, Json.getLong(probeMap, "probeBytes", MirrorProbe.DEFAULT.probeBytes())),
                    Math.max(1000, Json.getLong(probeMap, "timeoutMs", MirrorProbe.DEFAULT.timeoutMs())),
                    Math.max(0, Json.getLong(probeMap, "minFileSizeBytes", MirrorProbe.DEFAULT.minFileSizeBytes())));
        }

        Map<String, Object> modelsMap = Json.getMap(root, "models");
        Map<String, Object> defaults = defaultRoot();
        Map<String, Object> defaultModels = Json.getMap(defaults, "models");
        for (Map.Entry<String, Object> e : defaultModels.entrySet()) {
            // Start from the built-in defaults, then apply user overrides per key.
            Map<String, Object> merged = new LinkedHashMap<>(Json.asMap(e.getValue()));
            Map<String, Object> user = Json.asMap(modelsMap.get(e.getKey()));
            merged.putAll(user);
            ModelEntry entry = readModelEntry(e.getKey(), merged);
            if (entry != null) models.put(entry.id(), entry);
        }
        // User-defined extra models (unknown to defaults).
        for (Map.Entry<String, Object> e : modelsMap.entrySet()) {
            if (models.containsKey(e.getKey())) continue;
            ModelEntry entry = readModelEntry(e.getKey(), Json.asMap(e.getValue()));
            if (entry != null) models.put(entry.id(), entry);
        }

        Map<String, Object> enginesMap = new LinkedHashMap<>(Json.getMap(root, "engines"));
        Map<String, Object> defaultEngines = Json.getMap(defaults, "engines");
        for (Map.Entry<String, Object> e : defaultEngines.entrySet()) {
            Map<String, Object> def = Json.asMap(e.getValue());
            Map<String, Object> user = Json.asMap(enginesMap.get(e.getKey()));
            String modelId = Json.getString(user, "model", Json.getString(def, "model", null));
            String language = Json.getString(user, "language", Json.getString(def, "language", null));
            String type = Json.getString(user, "type", Json.getString(def, "type", null));
            List<String> languages = readLanguages(user, def);
            Map<String, String> options = userOptions(user, def);
            if (modelId != null && models.containsKey(modelId)) {
                engineModel.put(e.getKey(), modelId);
                if (language != null && !language.isBlank()) {
                    engineLanguage.put(e.getKey(), language.trim().toLowerCase(java.util.Locale.ROOT));
                }
                if (type != null && !type.isBlank()) {
                    engineType.put(e.getKey(), type.trim().toLowerCase(java.util.Locale.ROOT));
                }
                if (!languages.isEmpty()) engineLanguages.put(e.getKey(), languages);
                if (!options.isEmpty()) engineOptions.put(e.getKey(), options);
            }
        }
        for (Map.Entry<String, Object> e : enginesMap.entrySet()) {
            if (engineModel.containsKey(e.getKey())) continue;
            Map<String, Object> m = Json.asMap(e.getValue());
            String modelId = Json.getString(m, "model", null);
            String language = Json.getString(m, "language", null);
            String type = Json.getString(m, "type", null);
            List<String> languages = readLanguages(m, Map.of());
            Map<String, String> options = userOptions(m, Map.of());
            if (modelId != null && models.containsKey(modelId)) {
                engineModel.put(e.getKey(), modelId);
                if (language != null && !language.isBlank()) {
                    engineLanguage.put(e.getKey(), language.trim().toLowerCase(java.util.Locale.ROOT));
                }
                if (type != null && !type.isBlank()) {
                    engineType.put(e.getKey(), type.trim().toLowerCase(java.util.Locale.ROOT));
                }
                if (!languages.isEmpty()) engineLanguages.put(e.getKey(), languages);
                if (!options.isEmpty()) engineOptions.put(e.getKey(), options);
            }
        }
    }

    /** engines.<id>.languages array ∪ legacy language single value (deduped). */
    private static List<String> readLanguages(Map<String, Object> entry, Map<String, Object> defaults) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Object o : Json.getList(entry, "languages")) {
            if (o != null && !String.valueOf(o).isBlank()) {
                out.add(String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (out.isEmpty()) {
            String single = Json.getString(entry, "language", Json.getString(defaults, "language", null));
            if (single != null && !single.isBlank()) {
                out.add(single.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }

    /** engines.<id>.options merged over defaults (string values only). */
    private static Map<String, String> userOptions(Map<String, Object> entry, Map<String, Object> defaults) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> d : defaults.entrySet()) {
            if (d.getValue() != null) out.put(d.getKey(), String.valueOf(d.getValue()));
        }
        for (Map.Entry<String, Object> e : entry.entrySet()) {
            if (e.getValue() != null && !"language".equals(e.getKey()) && !"model".equals(e.getKey())
                    && !"type".equals(e.getKey()) && !"languages".equals(e.getKey())) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private ModelEntry readModelEntry(String id, Map<String, Object> m) {
            String kind = Json.getString(m, "kind", KIND_SHERPA_ARCHIVE);
        List<String> urls = Json.getStringList(m, "urls");
        List<FileEntry> files = new ArrayList<>();
        for (Object o : Json.getList(m, "files")) {
            Map<String, Object> f = Json.asMap(o);
            String name = Json.getString(f, "name", null);
            List<String> furls = Json.getStringList(f, "urls");
            if (name == null || furls.isEmpty()) continue;
            files.add(new FileEntry(name, furls,
                    Json.getString(f, "sha256", null),
                    Json.getLong(f, "minBytes", 1),
                    Json.getBool(f, "optional", false)));
        }
        if (urls.isEmpty() && files.isEmpty()) {
            VoiceCast.LOGGER.warn("Model '{}' has no urls/files; ignoring", id);
            return null;
        }
        return new ModelEntry(id, kind,
                Json.getLong(m, "sizeBytes", 0),
                Json.getString(m, "sha256", null),
                urls, List.copyOf(files));
    }

    // ------------------------------------------------------------------ save

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(defaultRoot()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            VoiceCast.LOGGER.warn("Failed to write {}", file, e);
        }
    }

    /** The complete default schema (used for both first-run generation and merging). */
    private static Map<String, Object> defaultRoot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", (long) SCHEMA_VERSION);
        root.put("_doc", "VoiceCast model catalog. 'urls' are tried fastest-first: with >1 URL each "
                + "mirror is probed and the quickest is used (others are fallbacks). 'sha256'/'sizeBytes' "
                + "verify vosk archives; loose-file entries validate via 'minBytes'. 'engines' maps a "
                + "recognizer engine id to the model it loads. Edit freely; delete the file to reset.");

        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("enabled", MirrorProbe.DEFAULT.enabled());
        probe.put("probeBytes", MirrorProbe.DEFAULT.probeBytes());
        probe.put("timeoutMs", MirrorProbe.DEFAULT.timeoutMs());
        probe.put("minFileSizeBytes", MirrorProbe.DEFAULT.minFileSizeBytes());
        root.put("mirrorProbe", probe);

        // SHA-256/size: TODO fill from the actual release archives (verification
        // is skipped while null); both archives live on the k2-fsa GitHub release.
        Map<String, Object> sherpaZhEn = new LinkedHashMap<>();
        sherpaZhEn.put("kind", KIND_SHERPA_ARCHIVE);
        sherpaZhEn.put("urls", List.of(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2"));
        putModel(root, MODEL_SHERPA_ZH_EN, sherpaZhEn);

        Map<String, Object> senseVoice = new LinkedHashMap<>();
        senseVoice.put("kind", KIND_SHERPA_ARCHIVE);
        senseVoice.put("urls", List.of(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"));
        putModel(root, MODEL_SHERPA_SENSEVOICE, senseVoice);

        String hfMirror = "https://hf-mirror.com/onnx-community/wav2vec2-lv-60-espeak-cv-ft-ONNX/resolve/main";
        String hf = "https://huggingface.co/onnx-community/wav2vec2-lv-60-espeak-cv-ft-ONNX/resolve/main";
        Map<String, Object> vocab = new LinkedHashMap<>();
        vocab.put("name", "vocab.json");
        vocab.put("minBytes", 1L);
        vocab.put("urls", List.of(hfMirror + "/vocab.json", hf + "/vocab.json"));

        Map<String, Object> q4 = new LinkedHashMap<>();
        q4.put("name", "model_q4.onnx");
        q4.put("minBytes", 150L * 1024 * 1024);
        q4.put("urls", List.of(hfMirror + "/onnx/model_q4.onnx", hf + "/onnx/model_q4.onnx"));

        Map<String, Object> ipa = new LinkedHashMap<>();
        ipa.put("kind", KIND_LOOSE_FILES);
        ipa.put("files", List.of(vocab, q4));
        putModel(root, MODEL_IPA, ipa);

        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("sherpa-zh-en", engineEntry(MODEL_SHERPA_ZH_EN, "sherpa-streaming",
                Map.of("languages", List.of("zh", "en"),
                        "encoder", "encoder-epoch-99-avg-1.int8.onnx",
                        "decoder", "decoder-epoch-99-avg-1.int8.onnx",
                        "joiner", "joiner-epoch-99-avg-1.int8.onnx",
                        "tokens", "tokens.txt",
                        "modeling_unit", "cjkchar+bpe",
                        "decoding_method", "modified_beam_search",
                        "num_threads", "2", "hotwords_score", "1.5")));
        engines.put("sherpa-sensevoice", engineEntry(MODEL_SHERPA_SENSEVOICE, "sherpa-sensevoice",
                Map.of("languages", List.of("zh", "en", "ja", "ko"),
                        "model", "model.int8.onnx",
                        "tokens", "tokens.txt",
                        "language", "auto", "itn", "true",
                        "num_threads", "2")));
        engines.put("ipa-phonemes", engineEntry(MODEL_IPA, "ipa", null));
        root.put("engines", engines);
        return root;
    }

    private static void putModel(Map<String, Object> root, String id, Map<String, Object> entry) {
        // NB: Json.getMap returns a THROWAWAY empty map for missing keys, so the
        // models section must be created and attached to root here (a previous
        // version put entries into the detached map and silently lost them,
        // leaving the whole catalog empty -> "No model configured for engine ...").
        Object models = root.get("models");
        if (!(models instanceof Map)) {
            models = new LinkedHashMap<String, Object>();
            root.put("models", models);
        }
        //noinspection unchecked
        ((Map<String, Object>) models).put(id, entry);
    }

    private static Map<String, Object> engineEntry(String modelId, String type, Map<String, Object> extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", modelId);
        if (type != null && !type.isBlank()) m.put("type", type);
        if (extras != null) m.putAll(extras);
        return m;
    }
}
