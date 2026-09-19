package com.theo.voicecast.server;

import com.theo.voicecast.api.Pronunciation;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Casting-time mode routing (issue #30, D-15 four-mode decision): candidate
 *  sets per mode, the engine-language composition, and the no-declaration
 *  pass-through. */
class CastModeRoutingTest {

    // Production-shaped ids: trigger = <spell>, chant lines = <spell>.chant.<lang>.<v>:<i>
    // (chain 0: line 0 = chant trigger, line 1 = body, line 2 = cast/release);
    // chant lines carry a single-language bucket exactly like langLine() builds them.
    private static final Pronunciation IGNIS_TRIGGER = new Pronunciation("wizardreal:ignis",
            List.of(), List.of(), Map.of("en", List.of("ignis"), "zh", List.of("火球")));
    private static final Pronunciation IGNIS_L1 = new Pronunciation("wizardreal:ignis.chant.en.0:0",
            List.of(), List.of(), Map.of("en", List.of("ignis, ember of the old tongue")));
    private static final Pronunciation IGNIS_BODY = new Pronunciation("wizardreal:ignis.chant.en.0:1",
            List.of(), List.of(), Map.of("en", List.of("let the ember answer")));
    private static final Pronunciation IGNIS_CAST = new Pronunciation("wizardreal:ignis.chant.en.0:2",
            List.of(), List.of(), Map.of("en", List.of("ignis")));
    private static final Pronunciation IGNIS_ZH_CAST = new Pronunciation("wizardreal:ignis.chant.zh.0:2",
            List.of(), List.of(), Map.of("zh", List.of("火球")));
    private static final Pronunciation UMBRA_TRIGGER = new Pronunciation("wizardreal:umbra_mortis",
            List.of(), List.of(), Map.of("en", List.of("umbra mortis")));
    private static final Pronunciation UMBRA_CAST = new Pronunciation("wizardreal:umbra_mortis.chant.en.0:1",
            List.of(), List.of(), Map.of("en", List.of("umbra mortis")));

    private static final Collection<Pronunciation> VOCAB = List.of(
            IGNIS_TRIGGER, IGNIS_L1, IGNIS_BODY, IGNIS_CAST, IGNIS_ZH_CAST, UMBRA_TRIGGER, UMBRA_CAST);

    private static List<String> ids(Collection<Pronunciation> vocab) {
        return vocab.stream().map(Pronunciation::id).toList();
    }

    @Test
    void noDeclarationIsBitIdentical() {
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, null, List.of()));
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, null, List.of("wizardreal:ignis")));
    }

    @Test
    void openKeepsFullVocabulary() {
        // OPEN = full vocabulary ∩ language buckets (supervisor ruling on the
        // P30 re-verification): the mode layer is a pass-through, every row
        // of every spell stays; the language projection narrows buckets.
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, CastMode.OPEN, List.of()));
    }

    @Test
    void openCoversAllSpellsRegardlessOfDeclaration() {
        // OPEN is the free-casting set: the declaration list plays no role.
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, CastMode.OPEN, List.of()));
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, CastMode.OPEN, List.of("wizardreal:umbra_mortis")));
    }

    @Test
    void chantConfirmNarrowsToDeclaredSpellAllAliases() {
        Collection<Pronunciation> confirm = VocabularyRouter.forSpells(
                VOCAB, CastMode.CHANT_CONFIRM, List.of("wizardreal:ignis"));
        assertEquals(List.of(
                "wizardreal:ignis",
                "wizardreal:ignis.chant.en.0:0",
                "wizardreal:ignis.chant.en.0:1",
                "wizardreal:ignis.chant.en.0:2",
                "wizardreal:ignis.chant.zh.0:2"), ids(confirm));
    }

    @Test
    void practiceConfirmSameCandidateShapeAsChantConfirm() {
        Collection<Pronunciation> practice = VocabularyRouter.forSpells(
                VOCAB, CastMode.PRACTICE_CONFIRM, List.of("wizardreal:umbra_mortis"));
        assertEquals(List.of("wizardreal:umbra_mortis", "wizardreal:umbra_mortis.chant.en.0:1"), ids(practice));
    }

    @Test
    void confirmWithoutDeclarationFallsBackToUnchanged() {
        // Defensive superset: no declared spell -> never narrower than declared data warrants.
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, CastMode.CHANT_CONFIRM, List.of()));
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, CastMode.PRACTICE_CONFIRM, List.of()));
        assertSame(VOCAB, VocabularyRouter.forSpells(VOCAB, CastMode.GRAY_NARROW, List.of()));
    }

    @Test
    void grayNarrowAddsTopConfusionNeighbors() {
        // wizardreal:falsum ships in confusion_neighbors.tsv with wizardreal:telum
        // as its top neighbor (M2E E-2 red list, rate-descending / judged-id-ascending).
        Pronunciation falsum = new Pronunciation("wizardreal:falsum", List.of(), List.of("falsum"));
        Pronunciation telum = new Pronunciation("wizardreal:telum", List.of(), List.of("telum"));
        Pronunciation bystander = new Pronunciation("wizardreal:ignis", List.of(), List.of("ignis"));
        Collection<Pronunciation> narrow = VocabularyRouter.forSpells(
                List.of(falsum, telum, bystander), CastMode.GRAY_NARROW, List.of("wizardreal:falsum"));
        assertEquals(List.of("wizardreal:falsum", "wizardreal:telum"), ids(narrow));
    }

    @Test
    void grayNarrowDeclaredOnlyWhenSpellHasNoNeighbors() {
        Pronunciation unknown = new Pronunciation("wizardreal:not_in_ledger", List.of(), List.of("x"));
        Collection<Pronunciation> narrow = VocabularyRouter.forSpells(
                List.of(unknown), CastMode.GRAY_NARROW, List.of("wizardreal:not_in_ledger"));
        assertEquals(List.of("wizardreal:not_in_ledger"), ids(narrow));
    }

    @Test
    void modeComposesWithEngineLanguageProjection() {
        // "∩单语言桶": mode first, then the engine-language buckets. Entries
        // stay in the vocabulary (language trim empties foreign buckets), so
        // assert on the heard aliases per id.
        Collection<Pronunciation> routed = VocabularyRouter.forLanguages(
                VocabularyRouter.forSpells(VOCAB, CastMode.CHANT_CONFIRM, List.of("wizardreal:ignis")),
                List.of("en"));
        assertEquals(5, routed.size());
        assertEquals(List.of("ignis"), aliasesOf(routed, "wizardreal:ignis"));
        assertEquals(List.of("ignis, ember of the old tongue"), aliasesOf(routed, "wizardreal:ignis.chant.en.0:0"));
        assertEquals(List.of("let the ember answer"), aliasesOf(routed, "wizardreal:ignis.chant.en.0:1"));
        assertEquals(List.of("ignis"), aliasesOf(routed, "wizardreal:ignis.chant.en.0:2"));
        assertTrue(aliasesOf(routed, "wizardreal:ignis.chant.zh.0:2").isEmpty());
    }

    @Test
    void openThenLanguageProjectionDropsForeignBuckets() {
        Collection<Pronunciation> routed = VocabularyRouter.forLanguages(
                VocabularyRouter.forSpells(VOCAB, CastMode.OPEN, List.of()), List.of("zh"));
        assertEquals(List.of("火球"), aliasesOf(routed, "wizardreal:ignis"));
        assertEquals(List.of("火球"), aliasesOf(routed, "wizardreal:ignis.chant.zh.0:2"));
        // en-only release rows survive the projection but are silent on a zh engine.
        assertTrue(aliasesOf(routed, "wizardreal:ignis.chant.en.0:2").isEmpty());
        assertTrue(aliasesOf(routed, "wizardreal:umbra_mortis.chant.en.0:1").isEmpty());
    }

    private static List<String> aliasesOf(Collection<Pronunciation> vocab, String id) {
        return vocab.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().aliases();
    }

    @Test
    void spellOfSplitsChantSchemeIds() {
        assertEquals("wizardreal:ignis", VocabularyRouter.spellOf("wizardreal:ignis"));
        assertEquals("wizardreal:ignis", VocabularyRouter.spellOf("wizardreal:ignis.chant.zh.0:2"));
        assertEquals("wizardreal:ignis", VocabularyRouter.spellOf("wizardreal:ignis.chant.0:2"));
        assertEquals("", VocabularyRouter.spellOf(null));
    }

    @Test
    void unparseableChantIdsPassThroughOpenKeptInConfirm() {
        // Marker present, no numeric chain index: OPEN keeps everything (full
        // vocabulary); CONFIRM keeps it as part of its declared spell.
        Pronunciation odd = new Pronunciation("wizardreal:odd.chant.en", List.of(), List.of("odd"));
        Collection<Pronunciation> open = VocabularyRouter.forSpells(List.of(odd), CastMode.OPEN, List.of());
        assertEquals(1, open.size());
        Collection<Pronunciation> confirm = VocabularyRouter.forSpells(
                List.of(odd), CastMode.CHANT_CONFIRM, List.of("wizardreal:odd"));
        assertEquals(1, confirm.size());
    }

    @Test
    void prefixSpellIdsDoNotCollide() {
        // "wizardreal:ignis_boost" must not be captured by declaring "wizardreal:ignis".
        Pronunciation boost = new Pronunciation("wizardreal:ignis_boost", List.of(), List.of("ignis boost"));
        Collection<Pronunciation> confirm = VocabularyRouter.forSpells(
                List.of(IGNIS_TRIGGER, boost), CastMode.CHANT_CONFIRM, List.of("wizardreal:ignis"));
        assertEquals(List.of("wizardreal:ignis"), ids(confirm));
    }

    @Test
    void emptyVocabularyPassesThrough() {
        assertSame(List.of(), VocabularyRouter.forSpells(List.of(), CastMode.OPEN, List.of()));
    }
}
