package com.theo.voicecast.model;

import com.theo.voicecast.VoiceCast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The VoiceCast model catalog ({@code config/voicecast/models.json}) — the
 * single source of truth for selectable engines (voiceCast#42, schema v2).
 *
 * <p><strong>One model = one engine.</strong> The model name is the engine id
 * and the model directory name ({@code config/voicecast/models/<name>});
 * there is no separate engines section. Schema (versioned via the
 * {@code version} key; v2-only — anything else is rewritten with defaults):
 * <pre>
 * {
 *   "version": 2,
 *   "mirrorProbe": { "enabled": true, "probeBytes": 262144, "timeoutMs": 5000, "minFileSizeBytes": 8388608 },
 *   "models": {
 *     "sherpa-zipformer-bilingual-zh-en-int8": {
 *       "properties": {
 *         "lang": ["zh", "en"], "type": "stream",
 *         "encoder": "encoder-epoch-99-avg-1.int8.onnx", "tokens": "tokens.txt",
 *         "bpe_vocab": "bpe.vocab", "num_threads": "2", ...
 *       },
 *       "source": { "kind": "sherpa-archive", "urls": ["https://..."], "sha256": "...", "size_bytes": 123 }
 *     },
 *     "wav2vec2-espeak-ipa": {
 *       "properties": { "type": "ipa" },
 *       "source": { "kind": "loose-files", "files": [ { "name": ..., "urls": [...], "minBytes": ... } ] }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>Selection rules ({@link #resolveModel(String)}): an exact model name
 * wins; otherwise a two-letter language code (or a common language name)
 * selects the <em>first declared</em> model whose {@code properties.lang}
 * contains it — declaration order in this file is the per-language default
 * precedence. The engine family for {@link com.theo.voicecast.api.engine.EngineFamilies}
 * comes from {@code properties.family} when declared, else derived from
 * {@code properties.type} + {@code source.kind} (stream → sherpa-streaming,
 * offline+sherpa-archive → sherpa-sensevoice, ipa/loose-files → ipa).
 *
 * <p>{@code properties.type = "denoiser"} marks an auxiliary speech-enhancement
 * model (e.g. gtcrn): it downloads through the same pipeline but is excluded
 * from the engine list and from engine selection.
 *
 * <p>v0 policy (AGENTS §3): no legacy schema reading or migration — a file
 * that is not valid v2 is replaced with the default catalog.
 */
public final class ModelConfig {
    public static final String FILE_NAME = "models.json";
    public static final int SCHEMA_VERSION = 2;
    /** JSON Schema artifact documenting the v2 catalog (workspace-relative). */
    public static final String SCHEMA_FILE = "docs/schemas/voicecast-models-v2.schema.json";
    public static final String KIND_SHERPA_ARCHIVE = "sherpa-archive";
    public static final String KIND_LOOSE_FILES = "loose-files";

    public record FileEntry(String name, List<String> urls, String sha256, long minBytes, boolean optional) {}

    /** One catalog model = one selectable engine. */
    public record ModelEntry(String id, String kind, long sizeBytes, String sha256,
                             List<String> urls, List<FileEntry> files,
                             String type, List<String> languages, Map<String, String> options) {}

    public record MirrorProbe(boolean enabled, long probeBytes, long timeoutMs, long minFileSizeBytes) {
        public static final MirrorProbe DEFAULT = new MirrorProbe(true, 262_144, 5_000, 8L * 1024 * 1024);
    }

    private final Map<String, ModelEntry> models = new LinkedHashMap<>();
    private MirrorProbe probe = MirrorProbe.DEFAULT;
    private final Path file;

    private ModelConfig(Path file) { this.file = file; }

    // --------------------------------------------------------------- queries

    public ModelEntry model(String modelId) { return models.get(modelId); }

    /** All model ids in declaration order, including auxiliary (denoiser) models. */
    public List<String> modelIds() { return List.copyOf(models.keySet()); }

    /** Selectable engine ids (declaration order; auxiliary denoiser models excluded). */
    public List<String> engineIds() {
        return models.values().stream()
                .filter(m -> !"denoiser".equals(m.type()))
                .map(ModelEntry::id)
                .toList();
    }

    /** The auxiliary speech-enhancement (denoiser) model, or null. */
    public ModelEntry denoiserModel() {
        for (ModelEntry m : models.values()) {
            if ("denoiser".equals(m.type())) return m;
        }
        return null;
    }

    /**
     * Resolve an engine request: exact model name first, then a two-letter
     * language code or common language name mapped to the first declared
     * model supporting it. Null when nothing matches (the {@code noop}
     * pseudo-engine is handled by callers).
     */
    public ModelEntry resolveModel(String arg) {
        if (arg == null || arg.isBlank()) return null;
        String a = arg.trim().toLowerCase(Locale.ROOT);
        ModelEntry exact = models.get(a);
        if (exact != null) return "denoiser".equals(exact.type()) ? null : exact;
        String lang = langCode(a);
        if (lang == null) return null;
        for (ModelEntry m : models.values()) {
            if (m.languages().contains(lang) && !"denoiser".equals(m.type())) return m;
        }
        return null;
    }

    /** Engine family key (EngineFamilies lookup) for a model, or null. */
    public String familyFor(String modelId) {
        ModelEntry m = models.get(modelId);
        if (m == null || "denoiser".equals(m.type())) return null;
        String explicit = m.options().get("family");
        if (explicit != null && !explicit.isBlank()) return explicit.trim().toLowerCase(Locale.ROOT);
        if ("ipa".equals(m.type()) || KIND_LOOSE_FILES.equals(m.kind())) return "ipa";
        if ("stream".equals(m.type())) return "sherpa-streaming";
        if ("offline".equals(m.type())) return "sherpa-sensevoice";
        return null;
    }

    /** Model kind (source.kind) for a model id, or null. */
    public String kindFor(String modelId) {
        ModelEntry m = models.get(modelId);
        return m == null ? null : m.kind();
    }

    /** Declared language codes for a model (two-letter), or empty. */
    public List<String> languagesFor(String modelId) {
        ModelEntry m = models.get(modelId);
        return m == null ? List.of() : m.languages();
    }

    /** Family option map (properties minus reserved keys), or empty. */
    public Map<String, String> optionsFor(String modelId) {
        ModelEntry m = models.get(modelId);
        return m == null ? Map.of() : m.options();
    }

    public MirrorProbe probe() { return probe; }

    public Path file() { return file; }

    /** Map a request token to a two-letter language code, or null. */
    public static String langCode(String token) {
        String t = token.trim().toLowerCase(Locale.ROOT);
        // ASCII two-letter codes only — CJK "codes" like 中文 route through the
        // name table below instead.
        if (t.length() == 2 && t.chars().allMatch(c -> c >= 'a' && c <= 'z')) return t;
        return switch (t) {
            case "english", "en-us", "en_us", "ingles" -> "en";
            case "chinese", "mandarin", "zhongwen", "中文", "汉语", "普通话" -> "zh";
            case "japanese", "日本語", "日语", "日文" -> "ja";
            case "korean", "한국어", "韩语", "韩文" -> "ko";
            case "cantonese", "粤语", "廣東話", "广东话" -> "yue";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ load

    public static ModelConfig load(Path runDir) {
        Path dir = runDir.resolve("config/voicecast");
        Path file = dir.resolve(FILE_NAME);
        ModelConfig cfg = new ModelConfig(file);
        boolean loaded = false;
        if (Files.isRegularFile(file)) {
            try {
                loaded = cfg.read(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                VoiceCast.LOGGER.error("Failed to parse {} ({}); rewriting with defaults", file, e.toString());
            }
            if (!loaded) {
                VoiceCast.LOGGER.error("{} is not a valid v2 catalog (no migrations in v0); rewriting with defaults", file);
            }
        } else {
            VoiceCast.LOGGER.info("No {} found; creating with default model catalog", file);
        }
        if (!loaded) cfg.readDefaults();
        cfg.save();
        return cfg;
    }

    /** Parse a v2 catalog. Returns false for anything else (caller resets to defaults). */
    private boolean read(Map<String, Object> root) {
        Map<String, Object> probeMap = Json.getMap(root, "mirrorProbe");
        if (!probeMap.isEmpty()) {
            probe = new MirrorProbe(
                    Json.getBool(probeMap, "enabled", MirrorProbe.DEFAULT.enabled()),
                    Math.max(4096, Json.getLong(probeMap, "probeBytes", MirrorProbe.DEFAULT.probeBytes())),
                    Math.max(1000, Json.getLong(probeMap, "timeoutMs", MirrorProbe.DEFAULT.timeoutMs())),
                    Math.max(0, Json.getLong(probeMap, "minFileSizeBytes", MirrorProbe.DEFAULT.minFileSizeBytes())));
        }

        Map<String, Object> modelsMap = Json.getMap(root, "models");
        if (modelsMap.isEmpty()) return false;
        for (Map.Entry<String, Object> e : modelsMap.entrySet()) {
            Map<String, Object> m = Json.asMap(e.getValue());
            Map<String, Object> source = Json.getMap(m, "source");
            // v2 shape requires the nested source object; anything else is a
            // legacy/foreign file -> caller rewrites with defaults (v0 policy).
            if (source.isEmpty()) return false;
            Map<String, Object> properties = Json.getMap(m, "properties");

            String id = e.getKey().trim();
            String kind = Json.getString(source, "kind", KIND_SHERPA_ARCHIVE);
            List<String> urls = Json.getStringList(source, "urls");
            List<FileEntry> files = new ArrayList<>();
            for (Object o : Json.getList(source, "files")) {
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
                continue;
            }

            String type = Json.getString(properties, "type", KIND_LOOSE_FILES.equals(kind) ? "ipa" : "stream");
            LinkedHashSet<String> langs = new LinkedHashSet<>();
            for (Object o : Json.getList(properties, "lang")) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    langs.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
                }
            }
            Map<String, String> options = new LinkedHashMap<>();
            for (Map.Entry<String, Object> p : properties.entrySet()) {
                if (p.getValue() == null || "lang".equals(p.getKey()) || "type".equals(p.getKey())) continue;
                options.put(p.getKey(), String.valueOf(p.getValue()));
            }
            models.put(id, new ModelEntry(id, kind,
                    Json.getLong(source, "size_bytes", Json.getLong(source, "sizeBytes", 0)),
                    Json.getString(source, "sha256", null),
                    urls, List.copyOf(files),
                    type.trim().toLowerCase(Locale.ROOT), List.copyOf(langs),
                    options.isEmpty() ? Map.of() : Map.copyOf(options)));
        }
        return !models.isEmpty();
    }

    private void readDefaults() {
        for (Map.Entry<String, Object> e : Json.getMap(defaultRoot(), "models").entrySet()) {
            if (!read(Map.of("models", Map.of(e.getKey(), e.getValue())))) {
                throw new IllegalStateException("default catalog entry failed to parse: " + e.getKey());
            }
        }
        probe = MirrorProbe.DEFAULT;
    }

    // ------------------------------------------------------------------ save

    /** Persist the catalog as-parsed (user entries are preserved verbatim). */
    public void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", (long) SCHEMA_VERSION);
        root.put("$schema", SCHEMA_FILE);
        root.put("_doc", "VoiceCast model catalog v2 - one model = one engine; the model name is the "
                + "engine id and the config/voicecast/models/<name> directory. properties.lang selects "
                + "per-language defaults by declaration order (first declared wins); properties.type is "
                + "stream|offline|ipa|denoiser (denoiser = auxiliary enhancement model, never an engine; "
                + "family override via properties.family); source carries kind + urls. "
                + "Selection accepts model names and two-letter language codes.");
        Map<String, Object> probeMap = new LinkedHashMap<>();
        probeMap.put("enabled", probe.enabled());
        probeMap.put("probeBytes", probe.probeBytes());
        probeMap.put("timeoutMs", probe.timeoutMs());
        probeMap.put("minFileSizeBytes", probe.minFileSizeBytes());
        root.put("mirrorProbe", probeMap);

        Map<String, Object> modelsMap = new LinkedHashMap<>();
        for (ModelEntry m : models.values()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            if (!m.languages().isEmpty()) properties.put("lang", m.languages());
            properties.put("type", m.type());
            properties.putAll(m.options());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("kind", m.kind());
            if (!m.urls().isEmpty()) source.put("urls", m.urls());
            if (m.sizeBytes() > 0) source.put("size_bytes", m.sizeBytes());
            if (m.sha256() != null) source.put("sha256", m.sha256());
            if (!m.files().isEmpty()) {
                List<Object> files = new ArrayList<>();
                for (FileEntry f : m.files()) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("name", f.name());
                    fm.put("urls", f.urls());
                    fm.put("minBytes", f.minBytes());
                    fm.put("optional", f.optional());
                    if (f.sha256() != null) fm.put("sha256", f.sha256());
                    files.add(fm);
                }
                source.put("files", files);
            }
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("properties", properties);
            mm.put("source", source);
            modelsMap.put(m.id(), mm);
        }
        root.put("models", modelsMap);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            VoiceCast.LOGGER.warn("Failed to write {}", file, e);
        }
    }

    /** The default catalog (also the schema example): bilingual streaming, SenseVoice offline, IPA. */
    private static Map<String, Object> defaultRoot() {
        Map<String, Object> bilingualProps = new LinkedHashMap<>();
        bilingualProps.put("lang", List.of("zh", "en"));
        bilingualProps.put("type", "stream");
        bilingualProps.put("encoder", "encoder-epoch-99-avg-1.int8.onnx");
        bilingualProps.put("decoder", "decoder-epoch-99-avg-1.int8.onnx");
        bilingualProps.put("joiner", "joiner-epoch-99-avg-1.int8.onnx");
        bilingualProps.put("tokens", "tokens.txt");
        bilingualProps.put("bpe_vocab", "bpe.vocab");
        bilingualProps.put("modeling_unit", "cjkchar+bpe");
        bilingualProps.put("decoding_method", "modified_beam_search");
        bilingualProps.put("num_threads", "2");
        bilingualProps.put("hotwords_score", "1.5");

        Map<String, Object> senseProps = new LinkedHashMap<>();
        senseProps.put("lang", List.of("zh", "en", "ja", "ko", "yue"));
        senseProps.put("type", "offline");
        senseProps.put("onnx", "model.int8.onnx");
        senseProps.put("tokens", "tokens.txt");
        senseProps.put("language", "auto");
        senseProps.put("itn", "true");
        senseProps.put("num_threads", "2");

        Map<String, Object> senseFullProps = new LinkedHashMap<>();
        senseFullProps.put("lang", List.of("zh", "en", "ja", "ko", "yue"));
        senseFullProps.put("type", "offline");
        // full archive carries float32 model.onnx (~937 MB) + int8; the fp32
        // file is the point of this entry (A/B against the int8-only one)
        senseFullProps.put("onnx", "model.onnx");
        senseFullProps.put("tokens", "tokens.txt");
        senseFullProps.put("language", "auto");
        senseFullProps.put("itn", "true");
        senseFullProps.put("num_threads", "2");

        Map<String, Object> ipaProps = new LinkedHashMap<>();
        ipaProps.put("type", "ipa");

        Map<String, Object> gtcrnProps = new LinkedHashMap<>();
        gtcrnProps.put("type", "denoiser");

        String senseInt8 = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2";
        String senseFull = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2";

        Map<String, Object> models = new LinkedHashMap<>();
        models.put("sherpa-zipformer-bilingual-zh-en-int8", model(bilingualProps,
                Map.of("kind", KIND_SHERPA_ARCHIVE,
                        "urls", List.of("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2"))));
        models.put("sherpa-sensevoice-small-int8", model(senseProps,
                Map.of("kind", KIND_SHERPA_ARCHIVE, "urls", List.of(senseInt8))));
        models.put("sherpa-sensevoice-full", model(senseFullProps,
                Map.of("kind", KIND_SHERPA_ARCHIVE, "urls", List.of(senseFull))));

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
        models.put("wav2vec2-espeak-ipa", model(ipaProps,
                Map.of("kind", KIND_LOOSE_FILES, "files", List.of(vocab, q4))));

        Map<String, Object> gtcrnFile = new LinkedHashMap<>();
        gtcrnFile.put("name", "gtcrn_simple.onnx");
        gtcrnFile.put("minBytes", 400_000L);
        gtcrnFile.put("optional", false);
        gtcrnFile.put("urls", List.of(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx"));
        models.put("gtcrn-simple-denoiser", model(gtcrnProps,
                Map.of("kind", KIND_LOOSE_FILES, "files", List.of(gtcrnFile))));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("models", models);
        return root;
    }

    private static Map<String, Object> model(Map<String, Object> properties, Map<String, Object> source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("properties", properties);
        m.put("source", source);
        return m;
    }
}
