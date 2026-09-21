package com.theo.voicecast.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Metaphone sanity: ASR substitution pairs must collapse to close keys (moved with Phonetics, C1b). */
class PhoneticsMetaphoneTest {

    @Test
    void asrSubstitutionPairsCollapse() {
        // chaos pronounced "kas" (ASR heard exactly that)
        assertEquals(Phonetics.metaphone("chaos"), Phonetics.metaphone("kas"));
        // vowel errors are invisible
        assertEquals(Phonetics.metaphone("falsum"), Phonetics.metaphone("falsome"));
        // phantom bolt vs fant bal — close but not identical
        float sim = Phonetics.score("phantom bolt", "fant bal");
        assertTrue(sim >= 0.7f, "phantom bolt vs fant bal scored " + sim);
    }

    @Test
    void distinctWordsStayDistinct() {
        assertTrue(Phonetics.score("ignis", "explosion") < 0.5f);
        assertTrue(Phonetics.score("fire", "water") < 0.6f);
    }
}
