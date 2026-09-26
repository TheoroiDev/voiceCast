package com.theo.voicecast.match;

import com.theo.voicecast.api.SessionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phonetic-tolerance cases gathered from the engbench failure corpus
 * (tools/benchmark/out/engbench): zh homophones (identical pinyin), latin
 * vowel errors and single-insertion near-misses must match, while distinct
 * spells must not cross-match. (Moved from WizardReal, C1b.)
 */
class SpellMatcherPhoneticTest {

    private static SessionVocabulary.Entry entry(String id, String... aliases) {
        return new SessionVocabulary.Entry(id, List.of(), List.of(aliases), null, null);
    }

    private static List<SessionVocabulary.Entry> roster() {
        return List.of(
                entry("wizardreal:falsum", "falsum", "虚影弹"),
                entry("wizardreal:fulgur", "雷序"),
                entry("wizardreal:tonitrus", "链电", "连锁雷"),
                entry("wizardreal:explosion", "explosion"));
    }

    @Test
    void zhHomophonesMatchViaPinyin() {
        // 链电 → lian dian; the recognizer heard 练电 — identical pinyin
        // (R8 human anchor 2026-09-26; the pre-rename 幻弹/换蛋 anchors died
        // with the alias rename — 换蛋 is NOT homophonic with 虚影弹)
        assertFuzzy("练电", "wizardreal:tonitrus");
        assertFuzzy("练电。", "wizardreal:tonitrus");
        // 雷序 → lei xu; heard 雷续 — identical pinyin
        assertFuzzy("雷续。", "wizardreal:fulgur");
    }

    @Test
    void latinVowelAndInsertionErrorsMatch() {
        // falsome = single insertion + vowel error on falsum
        assertFuzzy("falsome", "wizardreal:falsum");
        // vowel error inside explosion
        assertFuzzy("explion", "wizardreal:explosion");
    }

    @Test
    void distinctSpellsDoNotCrossMatch() {
        // phonetic layer must not turn unrelated words into matches
        // raw score below the 0.65 bar == pre-v2 null (bar lives in the adjudicator)
        assertTrue(SpellMatcher.match("banana", roster()).fuzzy() == null
                || SpellMatcher.match("banana", roster()).fuzzy().score() < 0.65f);
        assertTrue(SpellMatcher.match(" telefono ", roster()).fuzzy() == null
                || SpellMatcher.match(" telefono ", roster()).fuzzy().score() < 0.65f);
    }

    private static void assertFuzzy(String heard, String expectedEntry) {
        SpellMatcher.Result r = SpellMatcher.match(heard, roster());
        assertNotNull(r.fuzzy(), "no fuzzy match for heard=" + heard);
        assertEquals(expectedEntry, r.fuzzy().entryId(), "wrong entry for heard=" + heard);
    }
}
