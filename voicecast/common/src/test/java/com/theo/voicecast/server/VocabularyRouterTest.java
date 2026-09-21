package com.theo.voicecast.server;

import com.theo.voicecast.api.SessionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vocabulary routing: the selected engine's language decides the grammar bucket (D-A2). */
class VocabularyRouterTest {

    private static final SessionVocabulary.Entry BILINGUAL = new SessionVocabulary.Entry("spell:a", List.of("ˈɪɡnɪs"),
            List.of(), Map.of("en", List.of("ignis", "fire"), "zh", List.of("火球")), null);
    private static final SessionVocabulary.Entry LEGACY_ONLY = new SessionVocabulary.Entry("spell:b", List.of(),
            List.of("flat-alias"), null, null);
    private static final SessionVocabulary.Entry WITH_LEGACY_EXTRA = new SessionVocabulary.Entry("spell:c", List.of(),
            List.of("legacy-word"), Map.of("en", List.of("ignis")), null);

    private static Collection<String> aliases(Collection<SessionVocabulary.Entry> vocab, String id) {
        return vocab.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().aliases();
    }

    @Test
    void languageAgnosticEnginePassesThroughUnchanged() {
        Collection<SessionVocabulary.Entry> vocab = List.of(BILINGUAL, LEGACY_ONLY);
        assertSame(vocab, VocabularyRouter.forLanguage(vocab, null));
        assertSame(vocab, VocabularyRouter.forLanguage(vocab, " "));
    }

    @Test
    void emptyVocabularyPassesThrough() {
        Collection<SessionVocabulary.Entry> vocab = List.of();
        assertSame(vocab, VocabularyRouter.forLanguage(vocab, "en"));
    }

    @Test
    void engineLanguageFiltersBuckets() {
        Collection<SessionVocabulary.Entry> routed = VocabularyRouter.forLanguage(
                List.of(BILINGUAL, LEGACY_ONLY), "en");
        // en engine hears the en bucket; zh bucket is trimmed away.
        assertEquals(List.of("ignis", "fire"), aliases(routed, "spell:a"));
        assertEquals(List.of("flat-alias"), aliases(routed, "spell:b"));
        assertEquals("ˈɪɡnɪs", routed.stream().filter(p -> p.id().equals("spell:a"))
                .findFirst().orElseThrow().ipa().get(0));
    }

    @Test
    void chineseEngineHearsChineseBucket() {
        Collection<SessionVocabulary.Entry> routed = VocabularyRouter.forLanguage(
                List.of(BILINGUAL, LEGACY_ONLY), "zh");
        assertEquals(List.of("火球"), aliases(routed, "spell:a"));
        assertEquals(List.of("flat-alias"), aliases(routed, "spell:b"));
    }

    @Test
    void emptyBucketFallsBackToLegacyOnly() {
        Collection<SessionVocabulary.Entry> routed = VocabularyRouter.forLanguage(
                List.of(BILINGUAL, LEGACY_ONLY), "ko");
        assertEquals(List.of(), aliases(routed, "spell:a"));
        assertEquals(List.of("flat-alias"), aliases(routed, "spell:b"));
    }

    @Test
    void legacyExtrasReachEveryEngine() {
        Collection<SessionVocabulary.Entry> routedEn = VocabularyRouter.forLanguage(List.of(WITH_LEGACY_EXTRA), "en");
        Collection<SessionVocabulary.Entry> routedJa = VocabularyRouter.forLanguage(List.of(WITH_LEGACY_EXTRA), "ja");
        assertEquals(List.of("ignis", "legacy-word"), aliases(routedEn, "spell:c"));
        assertEquals(List.of("legacy-word"), aliases(routedJa, "spell:c"));
    }

    @Test
    void fullyLegacyVocabularyIsUntouched() {
        Collection<SessionVocabulary.Entry> vocab = List.of(LEGACY_ONLY);
        Collection<SessionVocabulary.Entry> routed = VocabularyRouter.forLanguage(vocab, "zh");
        assertSame(vocab, routed, "no-trim case must return the original collection");
        assertTrue(routed.contains(LEGACY_ONLY));
    }

    @Test
    void routedInstancesKeepIdsAndIpa() {
        Collection<SessionVocabulary.Entry> routed = VocabularyRouter.forLanguage(List.of(BILINGUAL), "zh");
        SessionVocabulary.Entry p = routed.iterator().next();
        assertEquals("spell:a", p.id());
        assertEquals(List.of("ˈɪɡnɪs"), p.ipa());
        // Routed instance is legacy-shaped: feeding it through again is a no-op.
        assertSame(routed, VocabularyRouter.forLanguage(routed, "zh"));
    }
}
