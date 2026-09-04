package com.theo.voicecast.api.engine;

import com.theo.voicecast.api.SpeechRecognizer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything an engine family needs to instantiate a recognizer, resolved from
 * the model catalog ({@code models.json engines.<id>}) — the config-side half of
 * the engine-family SPI (voiceCast#42).
 *
 * @param type      engine family key the factory was registered under
 * @param engineId  engines.<id> key the player selected
 * @param modelDir  resolved model directory (downloaded + verified)
 * @param languages normalized two-letter codes from language(s) (may be empty for
 *                  language-agnostic engines)
 * @param options   raw per-engine option map (e.g. num_threads, hotwords_score,
 *                  encoder/decoder/joiner file names) — families parse their own
 */
public record EngineSpec(
        String type,
        String engineId,
        Path modelDir,
        List<String> languages,
        Map<String, String> options
) {
    public EngineSpec {
        type = type == null ? "" : type;
        engineId = engineId == null ? "" : engineId;
        modelDir = modelDir == null ? Path.of("") : modelDir;
        languages = languages == null ? List.of() : List.copyOf(languages);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** String option or {@code fallback}. */
    public String option(String key, String fallback) {
        String v = options.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** Integer option or {@code fallback}. */
    public int intOption(String key, int fallback) {
        try {
            return Integer.parseInt(option(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Convenience: file under the model dir (options value or fallback name). */
    public Path file(String optionKey, String fallbackName) {
        return modelDir.resolve(option(optionKey, fallbackName));
    }

    /** First language hint for engines that take one (null when none declared). */
    public String primaryLanguage() {
        return languages.isEmpty() ? null : languages.get(0);
    }

    /** Marker interface implemented by recognizers that want the spec retained. */
    public interface SpecAware {
        EngineSpec spec();
    }

    /** Family factory: builds one recognizer instance per engine selection. */
    public interface RecognizerFactory {
        SpeechRecognizer create(EngineSpec spec) throws Exception;
    }
}
