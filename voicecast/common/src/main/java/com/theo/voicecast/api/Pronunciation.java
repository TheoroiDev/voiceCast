package com.theo.voicecast.api;

import java.util.List;

/**
 * Metadata describing a single "thing that can be said" (a spell, a command, etc.).
 * {@link #ipa()} contains one or more canonical IPA templates; {@link #aliases()}
 * contains textual command words for text-based recognizers and type-to-cast fallback.
 *
 * <p>Matcher thresholds live on the spell ({@code Spell#threshold()}), not here.
 */
public record Pronunciation(
        String id,
        List<String> ipa,
        List<String> aliases
) {
    public Pronunciation {
        ipa = ipa == null ? List.of() : List.copyOf(ipa);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
