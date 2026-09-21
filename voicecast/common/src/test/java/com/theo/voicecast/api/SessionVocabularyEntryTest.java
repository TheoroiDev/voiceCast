package com.theo.voicecast.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SessionVocabulary.Entry (the pre-v2 Pronunciation semantics, C1b):
 * language buckets, legacy bucket routing, flat view, spellId derivation. */
class SessionVocabularyEntryTest {

    @Test
    void legacyConstructorRoutesToEveryLanguage() {
        // Pre-0.4.0 shape: flat aliases = the legacy bucket -> every engine.
        SessionVocabulary.Entry e = new SessionVocabulary.Entry("s", List.of("ˈɪɡnɪs"), List.of("ignis", "fire"), null, null);
        assertEquals(List.of("ignis", "fire"), e.aliasesFor("en"));
        assertEquals(List.of("ignis", "fire"), e.aliasesFor("zh"));
        assertEquals(List.of("ignis", "fire"), e.aliasesFor("ko"));
        assertEquals(List.of("ignis", "fire"), e.aliasesFor(null));
        assertTrue(e.languages().isEmpty());
        assertEquals("s", e.spellId());
    }

    @Test
    void bucketsFlattenIntoAliasesView() {
        // LinkedHashMap: flatten preserves bucket insertion order (normalizeLanguages keeps it).
        java.util.Map<String, List<String>> buckets = new java.util.LinkedHashMap<>();
        buckets.put("en", List.of("ignis", "fire"));
        buckets.put("zh", List.of("火球"));
        SessionVocabulary.Entry e = new SessionVocabulary.Entry("s", List.of(), List.of(), buckets, null);
        // Flat view = union of buckets in map order (non-routing consumers unchanged).
        assertEquals(List.of("ignis", "fire", "火球"), e.aliases());
    }

    @Test
    void aliasesForRoutesBucketPlusLegacyExtras() {
        SessionVocabulary.Entry e = new SessionVocabulary.Entry("s", List.of(),
                List.of("legacy-word"), // extra legacy entry -> every engine
                Map.of("en", List.of("ignis"), "zh", List.of("火球")), null);
        assertEquals(List.of("ignis", "legacy-word"), e.aliasesFor("en"));
        assertEquals(List.of("火球", "legacy-word"), e.aliasesFor("zh"));
        // Empty bucket (e.g. ko untranslated) -> legacy only.
        assertEquals(List.of("legacy-word"), e.aliasesFor("ko"));
    }

    @Test
    void languageKeysAreNormalizedCaseInsensitive() {
        SessionVocabulary.Entry e = new SessionVocabulary.Entry("s", List.of(), List.of(),
                Map.of("ZH", List.of("火球")), null);
        assertEquals(List.of("火球"), e.aliasesFor("zh"));
        assertEquals(List.of("火球"), e.aliasesFor("ZH"));
        assertEquals(List.of("火球"), e.aliases());
    }

    @Test
    void nullArgumentsAreNormalized() {
        SessionVocabulary.Entry e = new SessionVocabulary.Entry("s", null, null, (Map<String, List<String>>) null, null);
        assertEquals(List.of(), e.ipa());
        assertEquals(List.of(), e.aliases());
        assertTrue(e.languages().isEmpty());
        assertEquals(List.of(), e.aliasesFor("en"));
    }

    @Test
    void emptyBucketsAreDropped() {
        SessionVocabulary.Entry e = new SessionVocabulary.Entry("s", List.of(), List.of("flat"),
                Map.of("en", List.of(), "zh", List.of("火球")), null);
        // Empty value lists are dropped; the flat extra stays legacy.
        assertTrue(e.languages().containsKey("zh"));
        assertEquals(List.of("火球", "flat"), e.aliasesFor("zh"));
        assertEquals(List.of("flat"), e.aliasesFor("en"));
    }

    @Test
    void spellIdDerivedFromChantMarker() {
        assertEquals("wizardreal:ignis", new SessionVocabulary.Entry(
                "wizardreal:ignis", List.of(), List.of(), null, null).spellId());
        assertEquals("wizardreal:mare", new SessionVocabulary.Entry(
                "wizardreal:mare.chant.en.0:2", List.of(), List.of(), null, null).spellId());
        assertEquals("wizardreal:mare", new SessionVocabulary.Entry(
                "wizardreal:mare.chant.0:2", List.of(), List.of(), null, null).spellId());
    }
}
