package com.theo.voicecast.match;

import com.theo.voicecast.api.Calibration;
import com.theo.voicecast.api.Decision;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.ThresholdHint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fusion priority of the semantic adjudicator (work order C1b §0.2): text
 * EXACT &gt; zipa EXACT &gt; text NEAR &gt; zipa NEAR &gt; AMBIGUOUS/
 * REJECTED — plus the margin verdict and the hint-driven tier suppression.
 * The cross-repo equivalence is pinned separately by
 * {@link EquivalenceVectorTest} on the shared JSON; these cases cover the
 * fusion machinery itself.
 */
class UtteranceAdjudicatorTest {

    private static final Calibration CAL = Calibration.DEFAULT;

    private static SessionVocabulary.Entry entry(String id, List<String> ipa,
                                                 List<String> aliases, ThresholdHint hint) {
        return new SessionVocabulary.Entry(id, ipa, aliases, null, hint);
    }

    private static final SessionVocabulary.Entry IGNIS =
            entry("wizardreal:ignis", List.of("ˈɪɡnɪs"), List.of("ignis"), null);
    private static final SessionVocabulary.Entry IGNIS_L1 =
            entry("wizardreal:ignis.chant.en.0:0", List.of(), List.of("ignis, ember of the old tongue"), null);
    private static final SessionVocabulary.Entry MARE =
            entry("wizardreal:mare", List.of("ˈmaːɾɛ"), List.of("mare"), null);
    private static final List<SessionVocabulary.Entry> VOCAB = List.of(IGNIS, IGNIS_L1, MARE);

    @Test
    void textExactBeatsCtcExact() {
        // verbatim trigger (tier 1) outranks a passing CTC posterior (tier 2)
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                VOCAB, CAL, "ignis", List.of(), Map.of("wizardreal:ignis", 0.4f), null);
        assertEquals(Decision.EXACT, a.decision());
        assertEquals("wizardreal:ignis", a.pronId());
        assertEquals(1.0f, a.score(), 1e-6f);
    }

    @Test
    void ctcExactBeatsTextNear() {
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                VOCAB, CAL, "ignus", List.of(), Map.of("wizardreal:ignis", 0.4f), null);
        assertEquals(Decision.EXACT, a.decision());
        assertEquals(0.4f, a.score(), 1e-6f);
        // the fuzzy text hit of the SAME entry collapses into the decision (no dup alternative)
        assertEquals(0, a.alternatives().size());
    }

    @Test
    void textNearBeatsPhonemeNear() {
        // both fuzzy lanes hit: the text lane (tier 3) outranks the phoneme lane (tier 4)
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                VOCAB, CAL, "mare", List.of("m","a","ɾ","ɛ"), Map.of(), null);
        assertEquals(Decision.EXACT, a.decision()); // verbatim on the trigger, not NEAR
    }

    @Test
    void phonemeNearAloneIsNear() {
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                List.of(MARE), CAL, "", List.of("m","a","ɾ","e"), Map.of(), null);
        assertEquals(Decision.NEAR, a.decision());
        assertEquals("wizardreal:mare", a.pronId());
        assertEquals(1.0f, a.score(), 1e-6f);
    }

    @Test
    void marginRejectWithWouldBePassIsAmbiguous() {
        Map<String, Float> posteriors = new java.util.LinkedHashMap<>();
        posteriors.put("wizardreal:mare", 0.6694f);
        posteriors.put("wizardreal:ignis", 0.655f);
        // post-margin map is all-zero (the recognizer gate), evidence keeps the gap
        Map<String, Float> zeroed = new java.util.LinkedHashMap<>();
        posteriors.forEach((k, v) -> zeroed.put(k, 0.0f));
        UtteranceAdjudicator.MarginInfo margin = new UtteranceAdjudicator.MarginInfo(
                "wizardreal:mare", 0.6694f, 0.655f, true);
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                VOCAB, CAL, "", List.of(), zeroed, margin);
        assertEquals(Decision.AMBIGUOUS, a.decision());
        assertEquals("wizardreal:mare", a.spellId());
        assertEquals(0.6694f, a.score(), 1e-4f);
        assertNotNull(a.diagnostics());
        assertEquals(true, a.diagnostics().marginRejected());
        assertTrue(a.diagnostics().ctcPresent());
    }

    @Test
    void marginRejectBelowThresholdIsPlainRejection() {
        Map<String, Float> zeroed = new java.util.LinkedHashMap<>();
        zeroed.put("wizardreal:mare", 0.0f);
        zeroed.put("wizardreal:ignis", 0.0f);
        UtteranceAdjudicator.MarginInfo margin = new UtteranceAdjudicator.MarginInfo(
                "wizardreal:mare", 0.05f, 0.045f, true);
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                VOCAB, CAL, "", List.of(), zeroed, margin);
        assertEquals(Decision.REJECTED, a.decision());
    }

    @Test
    void emptyUtteranceIsRejectedWithReason() {
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                VOCAB, CAL, "", List.of(), null, null);
        assertEquals(Decision.REJECTED, a.decision());
        assertEquals("empty_utterance", a.diagnostics().rejectionReason());
    }

    @Test
    void hintDisablesTierForOneEntry() {
        // ignis text tier disabled (hint 1.01): the same utterance no longer hits it
        SessionVocabulary.Entry suppressed = entry("wizardreal:ignis", List.of("ˈɪɡnɪs"),
                List.of("ignis"), new ThresholdHint(null, ThresholdHint.DISABLED, ThresholdHint.DISABLED));
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                List.of(suppressed), CAL, "ignis", List.of(), Map.of(), null);
        assertEquals(Decision.REJECTED, a.decision());
        // forward hint still applies on the CTC line
        UtteranceAdjudicator.Adjudication b = UtteranceAdjudicator.adjudicate(
                List.of(suppressed), CAL, "", List.of(),
                Map.of("wizardreal:ignis", 0.2f), null);
        assertEquals(Decision.EXACT, b.decision());
        assertEquals(0.2f, b.score(), 1e-6f);
    }

    @Test
    void middleLinesAreNeverCandidates() {
        // full chain so 0:1 is structurally a MIDDLE line (needs last index 2)
        SessionVocabulary.Entry first = entry("wizardreal:mare.chant.en.0:0",
                List.of(), List.of("o tide and storm"), null);
        SessionVocabulary.Entry mid = entry("wizardreal:mare.chant.en.0:1",
                List.of(), List.of("drown the field"), null);
        SessionVocabulary.Entry last = entry("wizardreal:mare.chant.en.0:2",
                List.of(), List.of("mare"), null);
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                List.of(MARE, first, mid, last), CAL, "", List.of(),
                Map.of("wizardreal:mare.chant.en.0:1", 0.9f), null);
        assertEquals(Decision.REJECTED, a.decision());
    }

    @Test
    void alternativesCarryRunnerUpsMaxThree() {
        SessionVocabulary.Entry aegis = entry("wizardreal:aegis", List.of("iːdʒɪs"), List.of("aegis"), null);
        SessionVocabulary.Entry falsum = entry("wizardreal:falsum", List.of("fɑlsəm"), List.of("falsum"), null);
        UtteranceAdjudicator.Adjudication a = UtteranceAdjudicator.adjudicate(
                List.of(MARE, aegis, falsum), CAL, "mare", List.of(),
                Map.of("wizardreal:aegis", 0.3f, "wizardreal:falsum", 0.2f), null);
        assertEquals(Decision.EXACT, a.decision());
        assertEquals("wizardreal:mare", a.pronId());
        assertEquals(2, a.alternatives().size());
        assertEquals("wizardreal:aegis", a.alternatives().get(0).pronId());
        assertEquals(0.3f, a.alternatives().get(0).score(), 1e-6f);
    }
}
