package com.theo.voicecast.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Loader-agnostic event bus used by VoiceCast common code. Loader modules
 * forward their own events here; consumers subscribe per event type.
 *
 * <p>This bus is intentionally tiny - no reflection, no annotations - to avoid
 * clashes with Forge's event bus or Fabric's callback system.
 */
public final class VoiceCastEvents {
    private static final Map<Class<?>, List<Consumer<?>>> LISTENERS = new ConcurrentHashMap<>();

    private VoiceCastEvents() {}

    public static <T> void subscribe(Class<T> type, Consumer<T> listener) {
        LISTENERS.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @SuppressWarnings("unchecked")
    public static <T> void post(T event) {
        List<Consumer<?>> list = LISTENERS.get(event.getClass());
        if (list == null) return;
        for (Consumer<?> c : list) {
            try {
                ((Consumer<T>) c).accept(event);
            } catch (Throwable t) {
                // never let a listener break the recognizer thread
                t.printStackTrace();
            }
        }
    }

    public static void clear() {
        LISTENERS.clear();
    }
}
