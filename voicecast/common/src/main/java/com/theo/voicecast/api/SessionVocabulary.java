package com.theo.voicecast.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ONE vocabulary push entry point (semantic contract v2, C1b §0.4):
 * {@code spell ids x language aliases x IPA templates x optional threshold
 * hint}, pushed once per game-side content load. Everything engine-shaped is
 * derived INSIDE voicecast from this data — ZIPA token templates, the qwen3
 * hotword list, the matcher surfaces — and no engine-shaped payload may
 * cross the mod boundary in the other direction.
 *
 * <p>Vocabulary id scheme (shared convention, same as the pre-v2 contract):
 * a trigger row's id is the spell id; a chant-line row's id is
 * {@code <spellId>.chant.<lang>.<variant>:<lineIndex>} (or legacy
 * {@code <spellId>.chant.<variant>:<lineIndex>} without a language). The
 * spell id of an entry is the id up to the {@code .chant.} marker. Line
 * position (first/last) is derived from the line indices of the entries
 * sharing the same chant group.
 */
public record SessionVocabulary(Collection<Entry> entries) {
    public SessionVocabulary {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static final SessionVocabulary EMPTY = new SessionVocabulary(List.of());

    /** One "thing that can be said": id, language buckets (or a flat legacy
     *  alias list), IPA templates, and the optional game-side threshold hint. */
    public record Entry(
            String id,
            List<String> ipa,
            List<String> aliases,
            Map<String, List<String>> languages,
            ThresholdHint threshold
    ) {
        public Entry {
            ipa = ipa == null ? List.of() : List.copyOf(ipa);
            languages = normalizeLanguages(languages);
            // Flat view = union of all buckets (in map order) + extra legacy entries.
            aliases = flatten(aliases, languages);
        }

        /** Spell id of this entry: the id up to the {@code .chant.} marker. */
        public String spellId() {
            String idStr = id == null ? "" : id;
            int marker = idStr.indexOf(".chant.");
            return marker < 0 ? idStr : idStr.substring(0, marker);
        }

        /** Aliases to feed an engine serving the given two-letter language code:
         *  the language bucket plus the legacy bucket. With no buckets at all
         *  this is the full flat list. */
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

        /** Aliases heard by engines serving any of the given language buckets:
         *  union of the buckets plus the legacy bucket. */
        public List<String> aliasesForLanguages(List<String> languageCodes) {
            if (languages.isEmpty()) return aliases;
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (String code : languageCodes) {
                if (code == null) continue;
                List<String> bucket = languages.get(code.trim().toLowerCase(Locale.ROOT));
                if (bucket != null) out.addAll(bucket);
            }
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
}
