package com.theo.voicecast.match;

import com.theo.voicecast.api.SessionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text alias matcher (moved from WizardReal's match package, C1b): verbatim
 * rules, fuzzy similarity, CJK boundaries and the TM-FIX tie rules.
 */
class SpellMatcherTest {

    private static SessionVocabulary.Entry entry(String id, String... aliases) {
        return new SessionVocabulary.Entry(id, List.of(), List.of(aliases), null, null);
    }

    private static List<SessionVocabulary.Entry> roster() {
        return List.of(
                entry("wizardreal:ignis", "ignis", "fire"),
                entry("wizardreal:explosion", "explosion magic"),
                // CJK alias survives normalization and uses CJK-adjacency word boundaries.
                entry("wizardreal:bakuretsu", "爆裂"));
    }

    @Test
    void exactAliasScoresOne() {
        SpellMatcher.Result r = SpellMatcher.match("ignis", roster());
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:ignis", r.verbatim().entryId());
        assertEquals(1.0f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void normalizationStripsCaseAndPunctuation() {
        SpellMatcher.Result r = SpellMatcher.match("  IGNIS!!! ", roster());
        assertNotNull(r.verbatim());
        assertEquals(1.0f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void wholeWordContainmentScoresNinetyPercent() {
        SpellMatcher.Result r = SpellMatcher.match("please cast fire now", roster());
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:ignis", r.verbatim().entryId());
        assertEquals(0.9f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void multiWordAliasContainmentScoresNinetyFivePercent() {
        SpellMatcher.Result r = SpellMatcher.match("cast explosion magic now", roster());
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:explosion", r.verbatim().entryId());
        assertEquals(0.95f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void shortFragmentMatchesAtPhoneticThreshold() {
        // "ign" is inside "ignis" but not at a word boundary -> no 0.9 shortcut;
        // char-Levenshtein gives 0.6, the phonetic layer 0.67 — the 0.65
        // threshold accepts short fragments of an alias by design (half-spoken
        // chants); wrong-SPELL safety is guaranteed by the collision audit
        // (worst cross-spell pair 0.33).
        SpellMatcher.Result r = SpellMatcher.match("ign", roster());
        assertNotNull(r.fuzzy());
        assertEquals("wizardreal:ignis", r.fuzzy().entryId());
    }

    @Test
    void smallTypoStillMatchesViaLevenshtein() {
        // distance("ignus","ignis") = 1 -> similarity 0.8 == the old MATCH_
        // THRESHOLD; the phonetic layer now scores the pair 1.0 (identical
        // consonant skeleton ign-s) — either way it matches.
        SpellMatcher.Result r = SpellMatcher.match("ignus", roster());
        assertNotNull(r.fuzzy());
        assertTrue(r.fuzzy().score() >= 0.8f);
    }

    @Test
    void largeDistanceFallsBelowDefaultThreshold() {
        assertTrue(SpellMatcher.match("ig", roster()).fuzzy() == null
                || SpellMatcher.match("ig", roster()).fuzzy().score() < 0.65f);
        // raw score below the 0.65 bar == pre-v2 null (bar lives in the adjudicator)
        assertTrue(SpellMatcher.match("abra cadabra", roster()).fuzzy() == null
                || SpellMatcher.match("abra cadabra", roster()).fuzzy().score() < 0.65f);
        assertNull(SpellMatcher.match("abra cadabra", roster()).verbatim());
    }

    @Test
    void unknownTokenAndEmptyInputMatchNothing() {
        SpellMatcher.Result unk = SpellMatcher.match("[unk]", roster());
        assertNull(unk.verbatim());
        assertTrue(unk.fuzzy() == null || unk.fuzzy().score() < 0.65f);
        SpellMatcher.Result blank = SpellMatcher.match("   ", roster());
        assertNull(blank.verbatim());
        assertNull(blank.fuzzy());
        SpellMatcher.Result nul = SpellMatcher.match(null, roster());
        assertNull(nul.verbatim());
        assertNull(nul.fuzzy());
    }

    @Test
    void cjkAliasMatchesInsideCjkRun() {
        SpellMatcher.Result r = SpellMatcher.match("火球爆裂啊", roster());
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:bakuretsu", r.verbatim().entryId());
        assertEquals(0.9f, r.verbatim().score(), 1e-6f);
    }

    @Test
    void cjkNormalizationKeepsLettersOnly() {
        assertEquals("爆裂", SpellMatcher.normalize("《爆裂》"));
        // punctuation becomes a word separator (hyphen is not kept)
        assertEquals("ig nis", SpellMatcher.normalize("Ig-Nis!"));
        // apostrophes are kept (English contractions)
        assertEquals("it's", SpellMatcher.normalize("It's!"));
        assertEquals("ignis", SpellMatcher.normalize("IGNIS!!!"));
    }

    @Test
    void bestScoringEntryWins() {
        List<SessionVocabulary.Entry> withIgni = List.of(
                entry("wizardreal:igni", "igni"),
                entry("wizardreal:ignis", "ignis"));
        SpellMatcher.Result r = SpellMatcher.match("ignis", withIgni);
        assertNotNull(r.verbatim());
        assertEquals("wizardreal:ignis", r.verbatim().entryId());
        assertTrue(r.verbatim().score() >= 1.0f - 1e-6f);
    }
}
