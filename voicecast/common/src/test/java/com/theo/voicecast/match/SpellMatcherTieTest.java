package com.theo.voicecast.match;

import com.theo.voicecast.api.SessionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alias tie-break cases (issue #29 P6 port of the lab TextMatcherTieTest —
 * TM-FIX, build/accent_calibration/c1ca_report.md): score ties are broken by
 * LONGEST alias first (most specific match); a full tie falls back to entry
 * id lexicographic order. (Moved from WizardReal, C1b.)
 */
class SpellMatcherTieTest {

    private static SessionVocabulary.Entry entry(String id, String... aliases) {
        return new SessionVocabulary.Entry(id, List.of(), List.of(aliases), null, null);
    }

    @Test
    void longestAliasWinsTheContainsTie() {
        // Artifact shape (sagitta/lumen C1c-a): a short alias (圣光) contained
        // inside another entry's longer alias (圣光矢) — both contain-match the
        // sentence at exactly 0.9. The old "first candidate wins" rule gave it
        // to the short alias listed first; the longest alias must win.
        List<SessionVocabulary.Entry> entries = List.of(
                entry("wizardreal:aaa", "圣光"),
                entry("wizardreal:bbb", "圣光矢"));
        SpellMatcher.Result r = SpellMatcher.match("圣光矢初现", entries);
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:bbb", r.verbatim().entryId());
        assertEquals(0.9f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void fullTieFallsBackToEntryIdOrder() {
        // Identical aliases, larger id listed first -> smaller id wins
        // (lab TextMatcherTieTest case 5; matches the old first-in-vocab
        // behavior on id-sorted vocab files).
        List<SessionVocabulary.Entry> twins = List.of(
                entry("wizardreal:bbb", "爆裂"),
                entry("wizardreal:aaa", "爆裂"));
        SpellMatcher.Result r = SpellMatcher.match("爆裂吧", twins);
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:aaa", r.verbatim().entryId());
        assertEquals(0.9f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void containsTieWithSameLengthResolvesById() {
        // Mixed sentence containing BOTH 圣光束 and 圣光矢 (lab case 3): both
        // contain-match at 0.9, same alias length -> smaller entry id wins.
        List<SessionVocabulary.Entry> entries = List.of(
                entry("wizardreal:lumen", "lumen", "holy beam", "圣光束", "圣光"),
                entry("wizardreal:sagitta", "sagitta", "holy arrow", "圣光矢", "光箭"));
        SpellMatcher.Result r = SpellMatcher.match("圣光束与圣光矢", entries);
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:lumen", r.verbatim().entryId());
        assertEquals(0.9f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void exactMatchBeatsLongerAlias() {
        // Score still outranks specificity (lab case 4): exact equality (1.0)
        // beats a longer alias that scores lower (0.6 via the phonetic
        // fallback), regardless of list order.
        List<SessionVocabulary.Entry> entries = List.of(
                entry("wizardreal:zz-long", "圣光矢初现"),
                entry("wizardreal:aa-exact", "圣光矢"));
        SpellMatcher.Result r = SpellMatcher.match("圣光矢", entries);
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:aa-exact", r.verbatim().entryId());
        assertEquals(1.0f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void unrelatedTextDoesNotMatch() {
        List<SessionVocabulary.Entry> entries = List.of(
                entry("wizardreal:lumen", "lumen", "holy beam", "圣光束", "圣光"),
                entry("wizardreal:sagitta", "sagitta", "holy arrow", "圣光矢", "光箭"));
        assertNull(SpellMatcher.match("完全没有咒语的话", entries).verbatim());
        assertTrue(SpellMatcher.match("完全没有咒语的话", entries).fuzzy() == null
                || SpellMatcher.match("完全没有咒语的话", entries).fuzzy().score() < 0.65f);
    }
}
