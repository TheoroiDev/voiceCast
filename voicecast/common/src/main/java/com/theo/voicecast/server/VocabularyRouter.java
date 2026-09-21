package com.theo.voicecast.server;

import com.theo.voicecast.api.SessionVocabulary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session-level vocabulary routing (0.4.0, voice overhaul D-A2): the selected
 * engine decides which language bucket of each vocabulary entry reaches its
 * recognizer. A session sees {@code bucket[engineLang] ∪ legacy}; engines
 * without a language (zipa-ipa, noop) get the vocabulary unchanged.
 *
 * <p>0.5.0 adds the casting-time mode layer (issue #30, D-15 four-mode
 * decision): {@link #forSpells} narrows the vocabulary to a mode's candidate
 * set before the language projection. A session with no declared mode
 * ({@code mode == null}) is never mode-routed — its behavior is bit-identical
 * to the pre-#30 routing.
 *
 * <p>Semantic contract v2 (C1b): operates on {@link SessionVocabulary.Entry};
 * ids, IPA templates and threshold hints pass through intact (only the alias
 * projection changes).
 *
 * <p>Pure and unit-testable: no Minecraft or engine types.
 */
final class VocabularyRouter {
    /** Chant-line id scheme marker ({@code <spell>.chant.<lang>.<v>:<i>} keyed,
     *  {@code <spell>.chant.<v>:<i>} legacy) — same convention as the shared
     *  vocabulary id scheme; what groups chant rows under their parent spell
     *  for the CONFIRM/GRAY_NARROW candidate sets. */
    private static final String CHANT_MARKER = ".chant.";

    private VocabularyRouter() {}

    /**
     * Project a vocabulary onto one engine language. Returns the original
     * collection when nothing needs trimming (language-agnostic engine or
     * fully-legacy vocabularies).
     */
    static Collection<SessionVocabulary.Entry> forLanguage(Collection<SessionVocabulary.Entry> vocabulary,
                                                           String language) {
        if (language != null && language.isBlank()) language = null;
        return forLanguages(vocabulary, language == null ? List.of() : List.of(language));
    }

    /** Multi-bucket routing (bilingual/multilingual engines): the session hears
     * the union of its language buckets plus the legacy bucket. */
    static Collection<SessionVocabulary.Entry> forLanguages(Collection<SessionVocabulary.Entry> vocabulary,
                                                            List<String> languages) {
        if (vocabulary.isEmpty()) return vocabulary;
        if (languages == null || languages.isEmpty()) return vocabulary;
        List<SessionVocabulary.Entry> out = new ArrayList<>(vocabulary.size());
        boolean anyChanged = false;
        for (SessionVocabulary.Entry p : vocabulary) {
            List<String> routed = p.aliasesForLanguages(languages);
            if (routed.equals(p.aliases())) {
                out.add(p);
            } else {
                out.add(new SessionVocabulary.Entry(p.id(), p.ipa(), routed, Map.of(), p.threshold()));
                anyChanged = true;
            }
        }
        return anyChanged ? List.copyOf(out) : vocabulary;
    }

    // =====================================================================
    // Casting-time mode routing (0.5.0, issue #30 / D-15 four-mode decision)
    // =====================================================================

    /**
     * Mode candidate set (D-15): project the vocabulary onto the candidate
     * set of {@code mode} before the engine-language projection.
     *
     * <ul>
     *   <li>{@code mode == null} — no declaration: the vocabulary unchanged
     *       (pre-#30 behavior, bit-identical).</li>
     *   <li>{@link CastMode#OPEN} — the full vocabulary: the candidate set is
     *       the whole word list; the only narrowing is the engine-language
     *       projection that every mode composes with. (The 0.5.0 trigger +
     *       release refinement was reverted — under the production
     *       SpellMatcher's Phonetics layer it re-shuffles rather than removes
     *       false triggers; supervisor ruling on the P30 re-verification.)</li>
     *   <li>{@link CastMode#CHANT_CONFIRM} / {@link CastMode#PRACTICE_CONFIRM}
     *       — the declared spells' every row (trigger + all chant lines).
     *       Empty declaration falls back to the unchanged vocabulary (never
     *       narrower than declared data warrants).</li>
     *   <li>{@link CastMode#GRAY_NARROW} — the declared spells plus their
     *       top-3 confusion neighbors ({@code confusion_neighbors.tsv}).</li>
     * </ul>
     *
     * <p>Spell association is by vocabulary id: the trigger row's id is the
     * spell id, chant-line ids carry the {@code .chant.} marker. Returns the
     * original collection when nothing needs trimming.
     */
    static Collection<SessionVocabulary.Entry> forSpells(Collection<SessionVocabulary.Entry> vocabulary,
                                                         CastMode mode,
                                                         Collection<String> spellIds) {
        if (mode == null || vocabulary.isEmpty()) return vocabulary;
        return switch (mode) {
            case OPEN -> vocabulary;
            case CHANT_CONFIRM, PRACTICE_CONFIRM -> forDeclared(vocabulary, spellIds, Set.of());
            case GRAY_NARROW -> forDeclared(vocabulary, spellIds, neighborsOf(spellIds));
        };
    }

    /** CONFIRM modes = the declared spells' every row (+ neighbor spells' rows
     *  for GRAY_NARROW). Rows of non-declared spells are dropped. */
    private static Collection<SessionVocabulary.Entry> forDeclared(Collection<SessionVocabulary.Entry> vocabulary,
                                                                   Collection<String> spellIds, Set<String> extra) {
        if (spellIds == null || spellIds.isEmpty()) return vocabulary;
        Set<String> spells = new LinkedHashSet<>();
        for (String id : spellIds) {
            if (id != null && !id.isBlank()) spells.add(id.trim());
        }
        if (spells.isEmpty()) return vocabulary;
        spells.addAll(extra);
        List<SessionVocabulary.Entry> out = new ArrayList<>(vocabulary.size());
        boolean anyChanged = false;
        for (SessionVocabulary.Entry p : vocabulary) {
            if (spells.contains(spellOf(p.id()))) {
                out.add(p);
            } else {
                anyChanged = true;
            }
        }
        return anyChanged ? List.copyOf(out) : vocabulary;
    }

    private static Set<String> neighborsOf(Collection<String> spellIds) {
        if (spellIds == null || spellIds.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String id : spellIds) {
            out.addAll(ConfusionNeighbors.neighbors(id));
        }
        return out;
    }

    /** Spell a vocabulary entry belongs to: the id up to the {@code .chant.}
     *  marker (chant lines), or the whole id (trigger rows / foreign ids). */
    static String spellOf(String id) {
        if (id == null) return "";
        int marker = id.indexOf(CHANT_MARKER);
        return marker < 0 ? id : id.substring(0, marker);
    }
}
