package com.theo.voicecast.server;

import com.theo.voicecast.api.Pronunciation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Session-level vocabulary routing (0.4.0, voice overhaul D-A2): the selected
 * engine decides which language bucket of each pronunciation reaches its
 * recognizer. A session sees {@code bucket[engineLang] ∪ legacy}; engines
 * without a language (ipa-phonemes, noop) get the vocabulary unchanged.
 *
 * <p>Pure and unit-testable: no Minecraft or engine types.
 */
final class VocabularyRouter {
    private VocabularyRouter() {}

    /**
     * Project a vocabulary onto one engine language. Returns the original
     * collection when nothing needs trimming (language-agnostic engine or
     * fully-legacy vocabularies).
     */
    static Collection<Pronunciation> forLanguage(Collection<Pronunciation> vocabulary, String language) {
        if (language != null && language.isBlank()) language = null;
        return forLanguages(vocabulary, language == null ? List.of() : List.of(language));
    }

    /** Multi-bucket routing (bilingual/multilingual engines): the session hears
     * the union of its language buckets plus the legacy bucket. */
    static Collection<Pronunciation> forLanguages(Collection<Pronunciation> vocabulary, List<String> languages) {
        if (vocabulary.isEmpty()) return vocabulary;
        if (languages == null || languages.isEmpty()) return vocabulary;
        List<Pronunciation> out = new ArrayList<>(vocabulary.size());
        boolean anyChanged = false;
        for (Pronunciation p : vocabulary) {
            List<String> routed = p.aliasesForLanguages(languages);
            if (routed.equals(p.aliases())) {
                out.add(p);
            } else {
                out.add(new Pronunciation(p.id(), p.ipa(), routed, Map.of()));
                anyChanged = true;
            }
        }
        return anyChanged ? List.copyOf(out) : vocabulary;
    }

    static Collection<Pronunciation> forLanguage0(Collection<Pronunciation> vocabulary, String language) {
        if (vocabulary.isEmpty()) return vocabulary;
        if (language == null || language.isBlank()) return vocabulary;
        List<Pronunciation> out = new ArrayList<>(vocabulary.size());
        boolean anyChanged = false;
        for (Pronunciation p : vocabulary) {
            List<String> routed = p.aliasesFor(language);
            if (routed.equals(p.aliases())) {
                out.add(p);
            } else {
                // The routed instance is legacy-shaped (no buckets): engines
                // consuming aliases() see exactly the routed aliases; id/ipa
                // (templateScores keying, CTC templates) pass through intact.
                out.add(new Pronunciation(p.id(), p.ipa(), routed, Map.of()));
                anyChanged = true;
            }
        }
        return anyChanged ? List.copyOf(out) : vocabulary;
    }
}
