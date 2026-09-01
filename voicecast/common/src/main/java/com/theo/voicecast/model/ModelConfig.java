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
 *   "engines": { "vosk-en-us": { "model": "vosk-model-small-en-us-0.15" }, ... }
 * }
 * </pre>
 */
public final class ModelConfig {
    public static final String FILE_NAME = "models.json";
    public static final int SCHEMA_VERSION = 1;

    public static final String KIND_VOSK_ARCHIVE = "vosk-archive";
    public static final String KIND_LOOSE_FILES = "loose-files";

    public static final String MODEL_VOSK_EN = "vosk-model-small-en-us-0.15";
    public static final String MODEL_VOSK_ZH = "vosk-model-small-cn-0.22";
    public static final String MODEL_VOSK_JA = "vosk-model-small-ja-0.22";
    public static final String MODEL_VOSK_KO = "vosk-model-small-ko-0.22";
    public static final String MODEL_IPA = "wav2vec2-espeak-ipa";

    public record FileEntry(String name, List<String> urls, String sha256, long minBytes, boolean optional) {}

    public record ModelEntry(String id, String kind, long sizeBytes, String sha256,
                             List<String> urls, List<FileEntry> files) {}

    public record MirrorProbe(boolean enabled, long probeBytes, long timeoutMs, long minFileSizeBytes) {
        public static final MirrorProbe DEFAULT = new MirrorProbe(true, 262_144, 5_000, 8L * 1024 * 1024);
    }

    private final Map<String, ModelEntry> models = new LinkedHashMap<>();
    private final Map<String, String> engineModel = new LinkedHashMap<>();
    private MirrorProbe probe = MirrorProbe.DEFAULT;
    private final Path file;

    private ModelConfig(Path file) { this.file = file; }

    public ModelEntry modelForEngine(String engineId) {
        String modelId = engineModel.get(engineId);
        return modelId == null ? null : models.get(modelId);
    }

    public String modelIdForEngine(String engineId) { return engineModel.get(engineId); }

    public ModelEntry model(String modelId) { return models.get(modelId); }

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

        Map<String, Object> enginesMap = Json.getMap(root, "engines");
        Map<String, Object> defaultEngines = Json.getMap(defaults, "engines");
        for (Map.Entry<String, Object> e : defaultEngines.entrySet()) {
            String modelId = Json.getString(Json.asMap(e.getValue()), "model", null);
            String user = Json.getString(Json.asMap(enginesMap.get(e.getKey())), "model", modelId);
            if (user != null && models.containsKey(user)) engineModel.put(e.getKey(), user);
        }
        for (Map.Entry<String, Object> e : enginesMap.entrySet()) {
            if (engineModel.containsKey(e.getKey())) continue;
            String modelId = Json.getString(Json.asMap(e.getValue()), "model", null);
            if (modelId != null && models.containsKey(modelId)) engineModel.put(e.getKey(), modelId);
        }
    }

    private ModelEntry readModelEntry(String id, Map<String, Object> m) {
        String kind = Json.getString(m, "kind", KIND_VOSK_ARCHIVE);
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

        Map<String, Object> voskEn = new LinkedHashMap<>();
        voskEn.put("kind", KIND_VOSK_ARCHIVE);
        voskEn.put("sizeBytes", 41_205_931L);
        voskEn.put("sha256", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498");
        voskEn.put("urls", List.of("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"));
        putModel(root, MODEL_VOSK_EN, voskEn);

        Map<String, Object> voskZh = new LinkedHashMap<>();
        voskZh.put("kind", KIND_VOSK_ARCHIVE);
        voskZh.put("sizeBytes", 43_898_754L);
        voskZh.put("sha256", "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba");
        voskZh.put("urls", List.of("https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"));
        putModel(root, MODEL_VOSK_ZH, voskZh);

        Map<String, Object> voskJa = new LinkedHashMap<>();
        voskJa.put("kind", KIND_VOSK_ARCHIVE);
        voskJa.put("sizeBytes", 49_704_573L);
        voskJa.put("sha256", "efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac");
        voskJa.put("urls", List.of("https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"));
        putModel(root, MODEL_VOSK_JA, voskJa);

        Map<String, Object> voskKo = new LinkedHashMap<>();
        voskKo.put("kind", KIND_VOSK_ARCHIVE);
        voskKo.put("sizeBytes", 86_914_329L);
        voskKo.put("sha256", "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989");
        voskKo.put("urls", List.of("https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"));
        putModel(root, MODEL_VOSK_KO, voskKo);

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

        Map<String, Object> f32 = new LinkedHashMap<>();
        f32.put("name", "model.onnx");
        f32.put("minBytes", 900L * 1024 * 1024);
        f32.put("optional", Boolean.TRUE); // q4 fallback (~1.3 GB), only if q4 missing
        f32.put("urls", List.of(hfMirror + "/onnx/model.onnx", hf + "/onnx/model.onnx"));

        Map<String, Object> ipa = new LinkedHashMap<>();
        ipa.put("kind", KIND_LOOSE_FILES);
        ipa.put("files", List.of(vocab, q4, f32));
        putModel(root, MODEL_IPA, ipa);

        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("vosk-en-us", engineEntry(MODEL_VOSK_EN));
        engines.put("vosk-zh-cn", engineEntry(MODEL_VOSK_ZH));
        engines.put("vosk-ja-jp", engineEntry(MODEL_VOSK_JA));
        engines.put("vosk-ko-kr", engineEntry(MODEL_VOSK_KO));
        engines.put("vosk-text", engineEntry(MODEL_VOSK_EN)); // legacy alias of vosk-en-us
        engines.put("ipa-phonemes", engineEntry(MODEL_IPA));
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

    private static Map<String, Object> engineEntry(String modelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", modelId);
        return m;
    }
}
