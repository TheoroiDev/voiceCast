package com.theo.voicecast.server;

import com.theo.voicecast.api.Pronunciation;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vocabulary routing: the selected engine's language decides the grammar bucket (D-A2). */
class VocabularyRouterTest {

    private static final Pronunciation BILINGUAL = new Pronunciation("spell:a", List.of("ˈɪɡnɪs"),
            List.of(), Map.of("en", List.of("ignis", "fire"), "zh", List.of("火球")));
    private static final Pronunciation LEGACY_ONLY = new Pronunciation("spell:b", List.of(),
            List.of("flat-alias"));
    private static final Pronunciation WITH_LEGACY_EXTRA = new Pronunciation("spell:c", List.of(),
            List.of("legacy-word"), Map.of("en", List.of("ignis")));

    private static Collection<String> aliases(Collection<Pronunciation> vocab, String id) {
        return vocab.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().aliases();
    }

    @Test
    void languageAgnosticEnginePassesThroughUnchanged() {
        Collection<Pronunciation> vocab = List.of(BILINGUAL, LEGACY_ONLY);
        assertSame(vocab, VocabularyRouter.forLanguage(vocab, null));
        assertSame(vocab, VocabularyRouter.forLanguage(vocab, " "));
    }

    @Test
    void emptyVocabularyPassesThrough() {
        Collection<Pronunciation> vocab = List.of();
        assertSame(vocab, VocabularyRouter.forLanguage(vocab, "en"));
    }

    @Test
    void engineLanguageFiltersBuckets() {
        Collection<Pronunciation> routed = VocabularyRouter.forLanguage(
                List.of(BILINGUAL, LEGACY_ONLY), "en");
        // en engine hears the en bucket; zh bucket is trimmed away.
        assertEquals(List.of("ignis", "fire"), aliases(routed, "spell:a"));
        assertEquals(List.of("flat-alias"), aliases(routed, "spell:b"));
        assertEquals("ˈɪɡnɪs", routed.stream().filter(p -> p.id().equals("spell:a"))
                .findFirst().orElseThrow().ipa().get(0));
    }

    @Test
    void chineseEngineHearsChineseBucket() {
        Collection<Pronunciation> routed = VocabularyRouter.forLanguage(
                List.of(BILINGUAL, LEGACY_ONLY), "zh");
        assertEquals(List.of("火球"), aliases(routed, "spell:a"));
        assertEquals(List.of("flat-alias"), aliases(routed, "spell:b"));
    }

    @Test
    void emptyBucketFallsBackToLegacyOnly() {
        Collection<Pronunciation> routed = VocabularyRouter.forLanguage(
                List.of(BILINGUAL, LEGACY_ONLY), "ko");
        assertEquals(List.of(), aliases(routed, "spell:a"));
        assertEquals(List.of("flat-alias"), aliases(routed, "spell:b"));
    }

    @Test
    void legacyExtrasReachEveryEngine() {
        Collection<Pronunciation> routedEn = VocabularyRouter.forLanguage(List.of(WITH_LEGACY_EXTRA), "en");
        Collection<Pronunciation> routedJa = VocabularyRouter.forLanguage(List.of(WITH_LEGACY_EXTRA), "ja");
        assertEquals(List.of("ignis", "legacy-word"), aliases(routedEn, "spell:c"));
        assertEquals(List.of("legacy-word"), aliases(routedJa, "spell:c"));
    }

    @Test
    void fullyLegacyVocabularyIsUntouched() {
        Collection<Pronunciation> vocab = List.of(LEGACY_ONLY);
        Collection<Pronunciation> routed = VocabularyRouter.forLanguage(vocab, "zh");
        assertSame(vocab, routed, "no-trim case must return the original collection");
        assertTrue(routed.contains(LEGACY_ONLY));
    }

    @Test
    void routedInstancesKeepIdsAndIpa() {
        Collection<Pronunciation> routed = VocabularyRouter.forLanguage(List.of(BILINGUAL), "zh");
        Pronunciation p = routed.iterator().next();
        assertEquals("spell:a", p.id());
        assertEquals(List.of("ˈɪɡnɪs"), p.ipa());
        // Routed instance is legacy-shaped: feeding it through again is a no-op.
        assertSame(routed, VocabularyRouter.forLanguage(routed, "zh"));
    }
}
