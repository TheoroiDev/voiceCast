package com.theo.voicecast.match;

import com.theo.voicecast.api.SessionVocabulary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S6 weighted phoneme matcher (moved from WizardReal's match package, C1b). */
class PhonemeMatcherTest {

    private static final String FULMEN_IPA = "ˈfʊlmɛn"; // f ʊ l m ɛ n

    private static SessionVocabulary.Entry entry(String id, List<String> ipa, String... aliases) {
        return new SessionVocabulary.Entry(id, ipa, List.of(aliases), null, null);
    }

    private static List<SessionVocabulary.Entry> fulmen() {
        return List.of(entry("wizardreal:fulmen", List.of(FULMEN_IPA), "fulmen"));
    }

    @Test
    void exactTemplateMatches() {
        PhonemeMatcher.Match m = PhonemeMatcher.match(List.of("f", "u", "l", "m", "e", "n"), fulmen());
        assertNotNull(m);
        assertEquals("wizardreal:fulmen", m.entryId());
        assertEquals(1.0f, m.score(), 1e-6f);
    }

    @Test
    void engineShiftedVowelsAndDroppedDarkLStillMatch() {
        // Real-world engine output for "fulmen": [f uː m ʌ n] — see workspace-root docs/IPA识别问题.md.
        // S6 weighted DP (wizardreal#29): the dropped syllable-final "l" now costs
        // the flat 0.6 indel (the former free target skip is subsumed) and the
        // ʌ->ə against the template's ɛ->e is an out-of-table substitution (1.0):
        // cost 1.6 over max(len)=6 -> 1 - 1.6/6 ~= 0.7333, still above 0.6.
        PhonemeMatcher.Match m = PhonemeMatcher.match(
                IpaText.normalizeTokens(List.of("f", "uː", "m", "ʌ", "n")), fulmen());
        assertNotNull(m);
        assertEquals("wizardreal:fulmen", m.entryId());
        assertEquals(1f - 1.6f / 6f, m.score(), 1e-4f);
    }

    @Test
    void contiguousTemplateInsideUtteranceGetsSubsequenceBonus() {
        PhonemeMatcher.Match m = PhonemeMatcher.match(
                List.of("a", "f", "u", "l", "m", "e", "n", "z"), fulmen());
        assertNotNull(m);
        assertEquals(0.92f, m.score(), 1e-6f);
    }

    @Test
    void unrelatedPhonemesFallBelowDefaultThreshold() {
        // C1b: the matcher returns the RAW best score; the acceptance bar
        // (0.6 default / per-entry hint) is applied by the adjudicator. The
        // pre-v2 null contract == raw best below the bar.
        assertTrue(PhonemeMatcher.match(List.of("x"), fulmen()).score() < 0.6f);
        assertTrue(PhonemeMatcher.match(List.of("z", "z", "z", "z", "z", "z"), fulmen()) == null
                || PhonemeMatcher.match(List.of("z", "z", "z", "z", "z", "z"), fulmen()).score() < 0.6f);
        assertNull(PhonemeMatcher.match(List.of(), fulmen()));
        assertNull(PhonemeMatcher.match(null, fulmen()));
    }

    // --------------------------------------------- S6 weighted costs (lab MatcherS6Test port)

    @Test
    void costScaleLockedAtTwo() {
        // S6-FINAL D1: the cost scale is locked at 2.0 (v0 hard cut, no knob).
        assertEquals(2.0f, PhonemeMatcher.COST_SCALE, 0f);
    }

    @Test
    void inTableConfusionScoresAboveEqualWeight() {
        // n/l confusion is in the table (raw 0.2224, scaled x2.0 -> 0.4448, no
        // clamp below the 1.0 ceiling): weighted similarity must beat the old
        // equal-weight distance (1 - 1/1 = 0).
        float nl = PhonemeMatcher.similarity(List.of("n"), List.of("l"));
        assertEquals(1f - 0.4448f, nl, 1e-4f);
        assertTrue(nl > 0f);
    }

    @Test
    void reverseConfusionClampsAtCeiling() {
        // reverse direction has its own (higher) cost: raw 0.5143 scales to
        // 1.0286 and CLAMPS to the D2 ceiling 1.0 -> similarity 0 (never more
        // expensive than the equal-weight out-of-table edit).
        float nl = PhonemeMatcher.similarity(List.of("n"), List.of("l"));
        float ln = PhonemeMatcher.similarity(List.of("l"), List.of("n"));
        assertEquals(0f, ln, 1e-4f);
        assertTrue(ln < nl);
    }

    @Test
    void outOfTableSubstitutionStaysUnscaledFlat() {
        // out-of-table pair keeps the UNSCALED flat 1.0 cost (S6-FINAL D1:
        // only table entries are scaled) -> same score as the old Levenshtein
        // similarity (unrelated pairs unchanged).
        float flat = PhonemeMatcher.similarity(List.of("ʃ"), List.of("ɤ"));
        assertEquals(0f, flat, 1e-4f);
        assertEquals(1.0f, PhonemeMatcher.subCost("ʃ", "ɤ"), 0f);
    }

    @Test
    void inTableSubCostIsScaleThenClamp() {
        // in-table subCost is clamp(raw x COST_SCALE, [0.1, 1]):
        // n->l = 0.4448 (no clamp), l->n = 1.0286 -> 1.0 (clamped).
        assertEquals(0.4448f, PhonemeMatcher.subCost("n", "l"), 1e-4f);
        assertEquals(1.0f, PhonemeMatcher.subCost("l", "n"), 0f);
    }

    @Test
    void identitySubstitutionStaysFree() {
        assertEquals(1f, PhonemeMatcher.similarity(List.of("a", "b"), List.of("a", "b")), 0f);
    }

    @Test
    void indelsCostFlatSixTenthsUnscaled() {
        // indels cost 0.6 in both directions, UNSCALED (D3; the grid scaled
        // only table entries): one extra token over max(len)=2 -> 1 - 0.6/2 = 0.7.
        assertEquals(0.7f, PhonemeMatcher.similarity(List.of("a", "b"), List.of("a")), 1e-4f);
        assertEquals(0.7f, PhonemeMatcher.similarity(List.of("a"), List.of("a", "b")), 1e-4f);
    }

    @Test
    void distantPairWeightedStillBeatsFlat() {
        // this specific distant pair still beats the flat Levenshtein
        // similarity (3 in-table/out-table subs + 1 indel = 3.0448 < 4 flat
        // edits). NOTE: under x2.0 this is instance-specific, not a general
        // invariant — heavily-confused table pairs can now cost more than the
        // flat 1.0 edit.
        float w = PhonemeMatcher.similarity(List.of("n", "ɤ", "ʈ"), List.of("l", "a", "ʃ", "b"));
        float f = 1f - (float) Levenshtein.distance(List.of("n", "ɤ", "ʈ"), List.of("l", "a", "ʃ", "b")) / 4f;
        assertTrue(w >= f - 1e-6f, w + " vs " + f);
    }

    @Test
    void costTableAssetShipsWithAllPairs() {
        // D2: the table is a tracked jar asset; 872 empirical pairs + 19
        // engine-swap C1 default-cost rows for the ZIPA symbol inventory
        // (one duplicate pair folded) — header comment lines not counted.
        // C1b: the asset moved to the voicecast jar (assets/voicecast/).
        assertEquals(891, PhonemeMatcher.tableForTest().size());
    }

    @Test
    void costTableParserRejectsBadLinesAndComments() throws IOException {
        assertTrue(PhonemeMatcher.parseCosts(List.of("# comment", "", "n\tl\t0.2224"))
                .containsKey("n\tl"));
        assertThrows(IOException.class,
                () -> PhonemeMatcher.parseCosts(List.of("only-two\tcolumns")));
    }
}
