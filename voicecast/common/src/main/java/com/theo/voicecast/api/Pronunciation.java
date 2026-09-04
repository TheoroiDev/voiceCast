package com.theo.voicecast.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metadata describing a single "thing that can be said" (a spell, a command, etc.).
 * {@link #ipa()} contains one or more canonical IPA templates; {@link #aliases()}
 * is the flat view of every language bucket plus any legacy extras — text-based
 * consumers that do not care about routing keep reading it unchanged.
 *
 * <p>Language buckets (0.4.0): {@link #languages()} maps a two-letter language
 * code (en/zh/ja/ko) to the aliases spoken in that language. The server routes
 * each session's vocabulary by the selected engine's language (the engine
 * decides the bucket): a session receives
 * {@link #aliasesFor(String) bucket[lang] ∪ legacy}. Flat aliases (constructed
 * without buckets) form the <em>legacy</em> bucket, which every engine receives.
 *
 * <p>Matcher thresholds live on the spell ({@code Spell#threshold()}), not here.
 */
public record Pronunciation(
        String id,
        List<String> ipa,
        List<String> aliases,
        Map<String, List<String>> languages
) {
    public Pronunciation {
        ipa = ipa == null ? List.of() : List.copyOf(ipa);
        languages = normalizeLanguages(languages);
        // Flat view = union of all buckets (in map order) + extra legacy entries.
        aliases = flatten(aliases, languages);
    }

    /**
     * @deprecated Flat constructor (pre-0.4.0 shape). All aliases form the
     * <em>legacy</em> bucket: they are routed into every engine's grammar.
     * Kept so addons compiled against 0.3.x keep working unchanged; prefer the
     * language-bucket constructor for language-aware vocabularies.
     */
    @Deprecated
    public Pronunciation(String id, List<String> ipa, List<String> aliases) {
        this(id, ipa, aliases, Map.of());
    }

    /**
     * Aliases to feed an engine serving the given two-letter language code:
     * the language bucket plus the legacy bucket (flat aliases not claimed by
     * any bucket). With no buckets at all this is the full flat list — the
     * pre-0.4.0 behavior.
     */
    public List<String> aliasesFor(String language) {
        if (languages.isEmpty()) return aliases;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> bucket = language == null
                ? null
                : languages.get(language.trim().toLowerCase(Locale.ROOT));
        if (bucket != null) out.addAll(bucket);
        out.addAll(legacyAliases());
        return List.copyOf(out);
    }

    /** Flat aliases not claimed by any language bucket (routed to every engine). */
    private List<String> legacyAliases() {
        if (aliases.isEmpty()) return List.of();
        LinkedHashSet<String> claimed = new LinkedHashSet<>();
        for (List<String> v : languages.values()) claimed.addAll(v);
        if (claimed.isEmpty()) return aliases;
        List<String> out = new ArrayList<>();
        for (String a : aliases) if (!claimed.contains(a)) out.add(a);
        return List.copyOf(out);
    }

    private static Map<String, List<String>> normalizeLanguages(Map<String, List<String>> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : in.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            List<String> values = List.copyOf(e.getValue());
            if (values.isEmpty()) continue;
            out.put(e.getKey().trim().toLowerCase(Locale.ROOT), values);
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    private static List<String> flatten(List<String> extraLegacy, Map<String, List<String>> languages) {
        if (languages.isEmpty()) {
            return extraLegacy == null ? List.of() : List.copyOf(extraLegacy);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (List<String> v : languages.values()) out.addAll(v);
        if (extraLegacy != null) out.addAll(extraLegacy);
        return List.copyOf(out);
    }
}
