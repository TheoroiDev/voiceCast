package com.theo.voicecast.api.event;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Recognizer lifecycle state plus a localized display message. The wire format
 * (and this record) carries a translation key + args instead of pre-formatted
 * English text, so the client renders it in its own language; see
 * {@code assets/voicecast/lang/*.json}.
 */
public record RecognizerStateEvent(RecognizerState state, String key, List<String> args) {
    public RecognizerStateEvent {
        key = key == null ? "" : key;
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** Client-side display component (falls back to the raw key when untranslated). */
    public Component toComponent() {
        return Component.translatable(key, args.toArray());
    }

    /** True when this event carries a displayable message. */
    public boolean hasMessage() {
        return !key.isEmpty();
    }
}
