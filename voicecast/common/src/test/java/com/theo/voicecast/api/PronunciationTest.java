package com.theo.voicecast.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pronunciation v2: language buckets, legacy bucket routing, flat view. */
class PronunciationTest {

    @Test
    void legacyConstructorRoutesToEveryLanguage() {
        // Pre-0.4.0 shape: flat aliases = the legacy bucket -> every engine.
        Pronunciation p = new Pronunciation("s", List.of("ˈɪɡnɪs"), List.of("ignis", "fire"));
        assertEquals(List.of("ignis", "fire"), p.aliasesFor("en"));
        assertEquals(List.of("ignis", "fire"), p.aliasesFor("zh"));
        assertEquals(List.of("ignis", "fire"), p.aliasesFor("ko"));
        assertEquals(List.of("ignis", "fire"), p.aliasesFor(null));
        assertTrue(p.languages().isEmpty());
    }

    @Test
    void bucketsFlattenIntoAliasesView() {
        // LinkedHashMap: flatten preserves bucket insertion order (normalizeLanguages keeps it).
        java.util.Map<String, List<String>> buckets = new java.util.LinkedHashMap<>();
        buckets.put("en", List.of("ignis", "fire"));
        buckets.put("zh", List.of("火球"));
        Pronunciation p = new Pronunciation("s", List.of(), List.of(), buckets);
        // Flat view = union of buckets in map order (non-routing consumers unchanged).
        assertEquals(List.of("ignis", "fire", "火球"), p.aliases());
    }

    @Test
    void aliasesForRoutesBucketPlusLegacyExtras() {
        Pronunciation p = new Pronunciation("s", List.of(),
                List.of("legacy-word"), // extra legacy entry -> every engine
                Map.of("en", List.of("ignis"), "zh", List.of("火球")));
        assertEquals(List.of("ignis", "legacy-word"), p.aliasesFor("en"));
        assertEquals(List.of("火球", "legacy-word"), p.aliasesFor("zh"));
        // Empty bucket (e.g. ko untranslated) -> legacy only.
        assertEquals(List.of("legacy-word"), p.aliasesFor("ko"));
    }

    @Test
    void languageKeysAreNormalizedCaseInsensitive() {
        Pronunciation p = new Pronunciation("s", List.of(), List.of(),
                Map.of("ZH", List.of("火球")));
        assertEquals(List.of("火球"), p.aliasesFor("zh"));
        assertEquals(List.of("火球"), p.aliasesFor("ZH"));
        assertEquals(List.of("火球"), p.aliases());
    }

    @Test
    void nullArgumentsAreNormalized() {
        Pronunciation p = new Pronunciation("s", null, null, (Map<String, List<String>>) null);
        assertEquals(List.of(), p.ipa());
        assertEquals(List.of(), p.aliases());
        assertTrue(p.languages().isEmpty());
        assertEquals(List.of(), p.aliasesFor("en"));
    }

    @Test
    void emptyBucketsAreDropped() {
        Pronunciation p = new Pronunciation("s", List.of(), List.of("flat"),
                Map.of("en", List.of(), "zh", List.of("火球")));
        // Empty value lists are dropped; the flat extra stays legacy.
        assertTrue(p.languages().containsKey("zh"));
        assertEquals(List.of("火球", "flat"), p.aliasesFor("zh"));
        assertEquals(List.of("flat"), p.aliasesFor("en"));
    }
}
