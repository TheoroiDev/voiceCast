package com.theo.voicecast.match;

import com.theo.voicecast.api.Alternative;
import com.theo.voicecast.api.Calibration;
import com.theo.voicecast.api.Decision;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.ThresholdHint;
import com.theo.voicecast.model.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared equivalence vectors (semantic contract v2, work order C1b G3): the
 * SAME c1b_vectors.json drives the voicecast adjudicator tests AND
 * WizardReal's ChantGate tests (byte-identical copies in both repos' test
 * resources). Expected values are the pre-v2 chain's verdicts on the shared
 * vocabulary — the vectors pin the mapping, and a mismatch is fixed by
 * changing the mapping, never the vector (向量即规格).
 *
 * <p>This side asserts the ADJUDICATOR: decision/spellId/pronId/score +
 * alternatives count. The reject-level overlay (level → per-entry threshold
 * hints) mirrors what WizardReal computes at push time — see the vector
 * file's {@code levelOverlay.rule}.
 */
class EquivalenceVectorTest {

    private static final float DELTA = 1e-4f;

    private record Vectors(Map<String, Object> doc, List<Map<String, Object>> vocabulary,
                           List<Map<String, Object>> vectors, Calibration calibration) {}

    private static Vectors load() throws IOException {
        String text = Files.readString(resource("c1b/c1b_vectors.json"), StandardCharsets.UTF_8);
        Map<String, Object> doc = Json.parseObject(text);
        Map<String, Object> cal = Json.getMap(doc, "calibration");
        Calibration calibration = new Calibration(
                (float) doubleOf(cal.get("forward")), (float) doubleOf(cal.get("phoneme")),
                (float) doubleOf(cal.get("text")), (float) doubleOf(cal.get("margin")));
        List<Map<String, Object>> vocabulary = new ArrayList<>();
        for (Object o : Json.getList(doc, "vocabulary")) vocabulary.add(Json.asMap(o));
        List<Map<String, Object>> vectors = new ArrayList<>();
        for (Object o : Json.getList(doc, "vectors")) vectors.add(Json.asMap(o));
        return new Vectors(doc, vocabulary, vectors, calibration);
    }

    @Test
    void sharedVectorsAdjudicateExactlyLikeThePreV2Chain() throws Exception {
        Vectors v = load();
        assertTrue(v.vectors().size() >= 30, "at least 30 shared vectors required");
        int mismatched = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> vec : v.vectors()) {
            UtteranceAdjudicator.Adjudication a = adjudicate(v, vec);
            Map<String, Object> expected = Json.getMap(vec, "expected");
            String id = Json.getString(vec, "id", "?");
            Decision expDecision = Decision.valueOf(Json.getString(expected, "decision", ""));
            String expSpell = Json.getString(expected, "spellId", "");
            String expPron = Json.getString(expected, "pronId", "");
            Object expScore = expected.get("score");
            Object expAlts = expected.get("altCount");
            List<String> problems = new ArrayList<>();
            if (a.decision() != expDecision) {
                problems.add("decision " + a.decision() + " != " + expDecision);
            }
            if (!a.spellId().equals(expSpell)) {
                problems.add("spellId '" + a.spellId() + "' != '" + expSpell + "'");
            }
            if (!a.pronId().equals(expPron)) {
                problems.add("pronId '" + a.pronId() + "' != '" + expPron + "'");
            }
            if (expScore != null && Math.abs(a.score() - (float) doubleOf(expScore)) > DELTA) {
                problems.add("score " + a.score() + " != " + expScore);
            }
            if (expAlts != null && a.alternatives().size() != (int) doubleOf(expAlts)) {
                problems.add("altCount " + a.alternatives().size() + " != " + expAlts + " ("
                        + a.alternatives().stream().map(Alternative::pronId).toList() + ")");
            }
            List<Map<String, Object>> expAltList = new ArrayList<>();
            if (expected.containsKey("alternatives")) {
                for (Object o : Json.getList(expected, "alternatives")) expAltList.add(Json.asMap(o));
            }
            for (int i = 0; i < expAltList.size() && i < a.alternatives().size(); i++) {
                Map<String, Object> e = Json.asMap(expAltList.get(i));
                Alternative got = a.alternatives().get(i);
                if (!got.pronId().equals(Json.getString(e, "pronId", ""))) {
                    problems.add("alt[" + i + "] " + got.pronId() + " != " + Json.getString(e, "pronId", ""));
                } else if (Math.abs(got.score() - (float) doubleOf(e.get("score"))) > DELTA) {
                    problems.add("alt[" + i + "] score " + got.score() + " != " + e.get("score"));
                }
            }
            if (!problems.isEmpty()) {
                mismatched++;
                if (failures.size() < 10) failures.add(id + ": " + String.join("; ", problems));
            }
        }
        assertEquals(0, mismatched, "equivalence vectors failed: " + failures);
    }

    // ------------------------------------------------------------- harness

    private UtteranceAdjudicator.Adjudication adjudicate(Vectors v, Map<String, Object> vec)
            throws IOException {
        Map<String, Object> overrides = vec.containsKey("entryThresholdOverride")
                ? Json.getMap(vec, "entryThresholdOverride") : Map.of();
        List<String> routed = vec.containsKey("routedLanguages")
                ? Json.getStringList(vec, "routedLanguages") : List.of();
        int level = (int) longOf(vec.get("level"));

        List<SessionVocabulary.Entry> entries = new ArrayList<>();
        for (Map<String, Object> e : v.vocabulary()) {
            String id = Json.getString(e, "id", "");
            List<String> ipa = Json.getStringList(e, "ipa");
            List<String> aliases = Json.getStringList(e, "aliases");
            Map<String, Object> langsRaw = Json.getMap(e, "languages");
            Map<String, List<String>> languages = new LinkedHashMap<>();
            for (Map.Entry<String, Object> en : langsRaw.entrySet()) {
                languages.put(en.getKey(), langsOf(en.getValue()));
            }
            // push-time routing (mirrors the session language projection)
            if (!routed.isEmpty() && !languages.isEmpty()
                    && langsRaw.keySet().stream().noneMatch(routed::contains)) {
                continue;
            }
            ThresholdHint hint = hintOf(id, overrides, level, LineShapes.parse(id) == null);
            entries.add(new SessionVocabulary.Entry(id, ipa, aliases, languages, hint));
        }

        String text = Json.getString(vec, "text", "");
        List<String> ipa = Json.getString(vec, "ipa", "").isBlank()
                ? List.of() : List.of(Json.getString(vec, "ipa", "").split(" "));
        Map<String, Object> ctcRaw = Json.getMap(vec, "ctc");
        Map<String, Float> ctc = new LinkedHashMap<>();
        for (Map.Entry<String, Object> en : ctcRaw.entrySet()) {
            ctc.put(en.getKey(), (float) doubleOf(en.getValue()));
        }
        // The recognizer computes margin evidence on the raw posteriors, then
        // zeroes them on rejection — reproduce that flow here.
        UtteranceAdjudicator.MarginInfo margin = marginInfo(ctc, v.calibration());
        Map<String, Float> postMargin = ctc;
        if (margin != null && margin.rejected()) {
            Map<String, Float> zeroed = new LinkedHashMap<>();
            for (Map.Entry<String, Float> en : ctc.entrySet()) zeroed.put(en.getKey(), 0.0f);
            postMargin = zeroed;
        }
        return UtteranceAdjudicator.adjudicate(entries, v.calibration(), text, ipa, postMargin, margin);
    }

    /** The recognizer-side margin computation (top1/top2/gap), mirroring
     *  ZipaPhonemeRecognizer.top1Top2 + applyCtcMargin. */
    static UtteranceAdjudicator.MarginInfo marginInfo(Map<String, Float> ctc, Calibration cal) {
        if (ctc == null || ctc.isEmpty()) return null;
        String top1Id = null;
        float top1 = 0f;
        for (Map.Entry<String, Float> e : ctc.entrySet()) {
            if (top1Id == null || e.getValue() > top1) {
                top1 = e.getValue();
                top1Id = e.getKey();
            }
        }
        float top2 = 0f;
        for (Map.Entry<String, Float> e : ctc.entrySet()) {
            if (!e.getKey().equals(top1Id) && e.getValue() > top2) top2 = e.getValue();
        }
        return new UtteranceAdjudicator.MarginInfo(top1Id, top1, top2, top1 - top2 < cal.margin());
    }

    /** Push-time threshold hints: per-entry override from the vector, then
     *  the reject-level overlay (see the file's levelOverlay.rule). */
    static ThresholdHint hintOf(String id, Map<String, Object> overrides, int level, boolean isTrigger) {
        ThresholdHint base = null;
        if (overrides.containsKey(id)) {
            Map<String, Object> o = Json.asMap(overrides.get(id));
            base = new ThresholdHint(
                    o.containsKey("forward") ? (float) doubleOf(o.get("forward")) : null,
                    o.containsKey("phoneme") ? (float) doubleOf(o.get("phoneme")) : null,
                    o.containsKey("text") ? (float) doubleOf(o.get("text")) : null);
        }
        if (level >= 1 && isTrigger) {
            return overlay(base, ThresholdHint.DISABLED);
        }
        if (level >= 2 && !isTrigger) {
            return overlay(base, ThresholdHint.DISABLED);
        }
        return base;
    }

    private static ThresholdHint overlay(ThresholdHint base, float disabled) {
        if (base == null) return new ThresholdHint(null, disabled, disabled);
        return new ThresholdHint(base.forward(),
                base.phoneme() == null ? disabled : base.phoneme(),
                base.text() == null ? disabled : base.text());
    }

    @SuppressWarnings("unchecked")
    private static List<String> langsOf(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) v) out.add(String.valueOf(o));
        return out;
    }

    private static double doubleOf(Object o) {
        return ((Number) o).doubleValue();
    }

    private static long longOf(Object o) {
        return ((Number) o).longValue();
    }

    private static Path resource(String name) throws IOException {
        try {
            return Path.of(EquivalenceVectorTest.class.getResource("/" + name).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
