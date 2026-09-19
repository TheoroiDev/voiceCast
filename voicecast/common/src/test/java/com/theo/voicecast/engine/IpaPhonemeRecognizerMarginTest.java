package com.theo.voicecast.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Margin-gate unit tests for {@link IpaPhonemeRecognizer#applyCtcMargin}
 * (issue #29 P6 port, S6-MATCHER WO D1/D3 — lab MatcherS6Test margin cases
 * adapted to the production margin home: the gate lives in the recognizer's
 * templateScores judgment, template-level top2 per work order D3).
 *
 * <p>The lab cases that involve WizardReal-side concerns are intentionally
 * not portable here: the forward-threshold rejection (top1 under 0.10) and
 * the spell-level runner-up rule live downstream in WizardReal's ChantGate /
 * lab Resolver respectively (template-level top2 is the ruled production
 * semantic — see the P6 port report).
 */
class IpaPhonemeRecognizerMarginTest {

    @AfterEach
    void restoreMargin() {
        IpaPhonemeRecognizer.CTC_MARGIN = 0.02f;
    }

    @Test
    void gapBelowMarginRejectsEvenWithTop1OverThreshold() {
        // 0.70 - 0.69 = 0.01 < CTC_MARGIN (0.02) -> ambiguous: every score zeroed.
        Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.69f);
        IpaPhonemeRecognizer.applyCtcMargin(scores);
        scores.values().forEach(v -> assertEquals(0.0f, v, 0f));
    }

    @Test
    void gapOverMarginFires() {
        // 0.70 - 0.67 = 0.03 >= 0.02 -> unambiguous, scores untouched.
        Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.67f);
        IpaPhonemeRecognizer.applyCtcMargin(scores);
        assertEquals(0.70f, scores.get("wizardreal:aa"), 1e-6f);
        assertEquals(0.67f, scores.get("wizardreal:bb"), 1e-6f);
    }

    @Test
    void gapExactlyAtMarginFires() {
        // WO D1 uses >=: pin the margin to the exact float difference so the
        // boundary is representation-independent (0.70f - 0.68f lands just
        // under 0.02f in binary).
        float saved = IpaPhonemeRecognizer.CTC_MARGIN;
        IpaPhonemeRecognizer.CTC_MARGIN = 0.70f - 0.68f;
        try {
            Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.68f);
            IpaPhonemeRecognizer.applyCtcMargin(scores);
            assertEquals(0.70f, scores.get("wizardreal:aa"), 1e-6f);
        } finally {
            IpaPhonemeRecognizer.CTC_MARGIN = saved;
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
        IpaPhonemeRecognizer.applyCtcMargin(scores);
        scores.values().forEach(v -> assertEquals(0.0f, v, 0f));
    }

    @Test
    void singleTemplateIsUnambiguous() {
        // no runner-up -> gap = top1 - 0 -> fires on the margin alone.
        Map<String, Float> scores = scores("wizardreal:aa", 0.65f);
        IpaPhonemeRecognizer.applyCtcMargin(scores);
        assertEquals(0.65f, scores.get("wizardreal:aa"), 1e-6f);
    }

    @Test
    void exactTieRejects() {
        Map<String, Float> scores = scores("wizardreal:aa", 0.70f, "wizardreal:bb", 0.70f);
        IpaPhonemeRecognizer.applyCtcMargin(scores);
        scores.values().forEach(v -> assertEquals(0.0f, v, 0f));
    }

    @Test
    void emptyMapIsNoOp() {
        Map<String, Float> scores = new LinkedHashMap<>();
        IpaPhonemeRecognizer.applyCtcMargin(scores);
        assertTrue(scores.isEmpty());
    }

    @Test
    void marginConstantIsTheCalibratedValue() {
        assertEquals(0.02f, IpaPhonemeRecognizer.CTC_MARGIN, 1e-9f);
    }

    private static Map<String, Float> scores(Object... kv) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Float) kv[i + 1]);
        return out;
    }
}
