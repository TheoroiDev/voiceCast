package com.theo.voicecast.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 F-a2 / issue #45 engine-side stopgap: a transcript that names ≥
 * {@link SherpaQwen3Recognizer#RECITAL_MIN_ALIASES} DISTINCT trigger aliases,
 * or one longer than {@link SherpaQwen3Recognizer#RECITAL_LENGTH_FACTOR}× the
 * longest alias, reads like a recitation of the hotword list and must be
 * dropped before the adjudicator's containment tier can match it verbatim.
 * Conservative boundaries: exactly 2 aliases and exactly 4× the longest
 * alias stay. N-value calibration is a product decision tracked in #45.
 */
class Qwen3RecitationGuardTest {

    private static final List<String> HOTWORDS = List.of("ignis", "aqua", "terra", "aer", "lux");

    @Test
    void normalShortTranscriptPasses() {
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "ignis"));
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "cast ignis now"));
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "ignis aqua"));
    }

    @Test
    void twoAliasesIsTheConservativeBoundary() {
        // Two distinct aliases in a short string: NOT a recitation.
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "ignis aqua"));
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "ignis aqua terra".substring(0, 10)));
    }

    @Test
    void threeDistinctAliasesDrop() {
        assertTrue(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "ignis aqua terra"),
                "≥ RECITAL_MIN_ALIASES distinct aliases = recitation");
        assertTrue(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "IGNIS AQUA TERRA"),
                "matching is case-insensitive");
    }

    @Test
    void concatenatedListEchoDrops() {
        String echo = String.join(", ", HOTWORDS) + ", " + String.join(", ", HOTWORDS);
        assertTrue(SherpaQwen3Recognizer.isRecitation(HOTWORDS, echo),
                "the lab L2b shape: the model echoes the hotword list itself");
    }

    @Test
    void duplicateAliasesInTheHotwordListCountOnce() {
        // R2 rework (review low item): the count is DISTINCT alias strings, not
        // list positions — a routing list repeating an alias must not inflate
        // the match count toward RECITAL_MIN_ALIASES.
        List<String> duplicated = List.of("ignis", "ignis", "aqua", "aqua");
        assertFalse(SherpaQwen3Recognizer.isRecitation(duplicated, "ignis aqua"),
                "4 list entries but only 2 distinct alias strings = NOT a recitation");
        assertFalse(SherpaQwen3Recognizer.isRecitation(List.of("lux", "lux", "lux"), "lux"),
                "the same alias three times still counts once");
        assertTrue(SherpaQwen3Recognizer.isRecitation(
                        List.of("ignis", "ignis", "aqua", "aqua", "terra"), "ignis aqua terra"),
                "3 genuinely distinct aliases still drop even with duplicates present");
    }

    @Test
    void lengthOverFourTimesLongestAliasDrops() {
        // longest alias is 5 chars → 4× = 20.
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "x".repeat(20)),
                "exactly 4× the longest alias stays (conservative boundary)");
        assertTrue(SherpaQwen3Recognizer.isRecitation(HOTWORDS, "x".repeat(21)),
                "longer than 4× the longest alias = recitation");
    }

    @Test
    void emptyInputsNeverDrop() {
        assertFalse(SherpaQwen3Recognizer.isRecitation(List.of(), "anything at all"));
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, ""));
        assertFalse(SherpaQwen3Recognizer.isRecitation(HOTWORDS, null));
        assertFalse(SherpaQwen3Recognizer.isRecitation(null, "ignis"));
    }
}
