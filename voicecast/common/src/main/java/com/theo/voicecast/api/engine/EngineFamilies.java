package com.theo.voicecast.api.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of engine families ({@code type -> RecognizerFactory}) — the
 * extension point that lets addon mods plug new ASR architectures into
 * voicecast without a voicecast release (voiceCast#42).
 *
 * <p>Lifecycle: families are registered at mod init (voicecast registers its
 * builtin families; addons register theirs). After init the map is read-only in
 * practice. Unregistered types surface as engine-FAILED with a clear error when
 * a player selects such an engine — the server never crashes.
 *
 * <p>Layering: <b>type → factory</b> is this registry (compiled-in builtin set +
 * addon registrations); <b>engines.<id> → model/type/options</b> is configuration
 * (models.json) — adding or swapping a compatible model never requires code.
 */
public final class EngineFamilies {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCast");

    private static final Map<String, EngineSpec.RecognizerFactory> FAMILIES = new ConcurrentHashMap<>();

    private EngineFamilies() {}

    /**
     * Register a family. Duplicate types throw — families come from mod init
     * where a clash is a programming error, not a runtime condition.
     */
    public static void register(String type, EngineSpec.RecognizerFactory factory) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        if (factory == null) throw new IllegalArgumentException("factory must not be null");
        String key = type.trim().toLowerCase(Locale.ROOT);
        var prev = FAMILIES.putIfAbsent(key, factory);
        if (prev != null) {
            throw new IllegalStateException("engine family already registered: " + key);
        }
        LOGGER.info("Registered engine family '{}'", key);
    }

    /** Factory for a type, or {@code null} when the family was never registered. */
    public static EngineSpec.RecognizerFactory get(String type) {
        if (type == null) return null;
        return FAMILIES.get(type.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> types() {
        return Set.copyOf(FAMILIES.keySet());
    }

    /** Test/reset hook. */
    static void clearForTests() {
        FAMILIES.clear();
    }

    // Convenience factories kept here so builtin registration reads cleanly.

    /** Factory that always returns a fresh instance of the given class. */
    public static EngineSpec.RecognizerFactory of(java.util.function.Supplier<?> supplier) {
        return spec -> (com.theo.voicecast.api.SpeechRecognizer) supplier.get();
    }

    /** Collect helper for tests. */
    public static List<String> sortedTypes() {
        var out = new ArrayList<>(FAMILIES.keySet());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
