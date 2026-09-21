package com.theo.voicecast.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Margin-gate unit tests for {@link ZipaPhonemeRecognizer#applyCtcMargin} —
 * migrated unchanged from the espeak backend ({@code IpaPhonemeRecognizer}
 * margin test, issue #29 P6 port, S6-MATCHER WO D1/D3): the gate lives in the
 * recognizer's templateScores judgment, template-level top2 per work order D3.
 * The {@code ctcPresent}/templateScores contract downstream (WizardReal
 * ChantGate) is unchanged.
 */
class ZipaPhonemeRecognizerMarginTest {

    @AfterEach
    void restoreMargin() {
        ZipaPhonemeRecognizer.CTC_MARGIN = 0.02f;
    }

    @Test
    void gapBelowMarginRejectsEvenWithTop1OverThreshold() {
        // 0.70 - 0.69 = 0.01 < CTC_MARGIN (0.02) -> ambiguous: every score zeroed.
        Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.69f);
        ZipaPhonemeRecognizer.applyCtcMargin(scores);
        scores.values().forEach(v -> assertEquals(0.0f, v, 0f));
    }

    @Test
    void gapOverMarginFires() {
        // 0.70 - 0.67 = 0.03 >= 0.02 -> unambiguous, scores untouched.
        Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.67f);
        ZipaPhonemeRecognizer.applyCtcMargin(scores);
        assertEquals(0.70f, scores.get("wizardreal:aa"), 1e-6f);
        assertEquals(0.67f, scores.get("wizardreal:bb"), 1e-6f);
    }

    @Test
    void gapExactlyAtMarginFires() {
        // WO D1 uses >=: pin the margin to the exact float difference so the
        // boundary is representation-independent (0.70f - 0.68f lands just
        // under 0.02f in binary).
        float saved = ZipaPhonemeRecognizer.CTC_MARGIN;
        ZipaPhonemeRecognizer.CTC_MARGIN = 0.70f - 0.68f;
        try {
            Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.68f);
            ZipaPhonemeRecognizer.applyCtcMargin(scores);
            assertEquals(0.70f, scores.get("wizardreal:aa"), 1e-6f);
        } finally {
            ZipaPhonemeRecognizer.CTC_MARGIN = saved;
        }
    }

    @Test
    void closeRunnerUpTemplateRejects() {
        // Template-level top2 (production D3 semantic): a close second
        // TEMPLATE — not a different spell — is enough to reject. Three
        // templates: aa 0.70, aa2 0.69, bb 0.50 -> top2 = 0.69 -> rejected.
        Map<String, Float> scores = scores(
                "wizardreal:aa", 0.70f,
                "wizardreal:aa.chant.1:1", 0.69f,
                "wizardreal:bb", 0.50f);
        ZipaPhonemeRecognizer.applyCtcMargin(scores);
        scores.values().forEach(v -> assertEquals(0.0f, v, 0f));
    }

    @Test
    void singleTemplateIsUnambiguous() {
        // no runner-up -> gap = top1 - 0 -> fires on the margin alone.
        Map<String, Float> scores = scores("wizardreal:aa", 0.65f);
        ZipaPhonemeRecognizer.applyCtcMargin(scores);
        assertEquals(0.65f, scores.get("wizardreal:aa"), 1e-6f);
    }

    @Test
    void exactTieRejects() {
        Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.70f);
        ZipaPhonemeRecognizer.applyCtcMargin(scores);
        scores.values().forEach(v -> assertEquals(0.0f, v, 0f));
    }

    @Test
    void emptyMapIsNoOp() {
        Map<String, Float> scores = new LinkedHashMap<>();
        ZipaPhonemeRecognizer.applyCtcMargin(scores);
        assertTrue(scores.isEmpty());
    }

    @Test
    void marginConstantIsTheCalibratedValue() {
        assertEquals(0.02f, ZipaPhonemeRecognizer.CTC_MARGIN, 1e-9f);
    }

    private static Map<String, Float> scores(Object... kv) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Float) kv[i + 1]);
        return out;
    }
}
