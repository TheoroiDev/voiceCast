package com.theo.voicecast.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Registry for {@link SpeechRecognizer} backends. Addons can register their own
 * engines (Whisper, external library bridges, etc.) before the client starts a
 * recognition session.
 */
public final class RecognizerRegistry {
    private static final Map<String, Supplier<SpeechRecognizer>> FACTORIES = new LinkedHashMap<>();
    private static String defaultId = "sherpa-ipa";

    private RecognizerRegistry() {}

    public static void register(String id, Supplier<SpeechRecognizer> factory) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (factory == null) throw new IllegalArgumentException("factory");
        FACTORIES.put(id, factory);
    }

    public static void setDefault(String id) {
        defaultId = id;
    }

    public static String defaultId() {
        return defaultId;
    }

    public static SpeechRecognizer create(String id) {
        Supplier<SpeechRecognizer> f = FACTORIES.get(id);
        if (f == null) throw new IllegalArgumentException("Unknown recognizer: " + id);
        return f.get();
    }

    public static Set<String> ids() {
        return java.util.Collections.unmodifiableSet(FACTORIES.keySet());
    }

    public static Collection<Supplier<SpeechRecognizer>> factories() {
        return FACTORIES.values();
    }
}
