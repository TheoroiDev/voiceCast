package com.theo.voicecast.match;

import com.theo.voicecast.api.Alternative;
import com.theo.voicecast.api.Calibration;
import com.theo.voicecast.api.Decision;
import com.theo.voicecast.api.RecognitionDiagnostics;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.ThresholdHint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantic utterance adjudicator (semantic contract v2, work order C1b
 * §0.2): voicecast owns the decision. Fuses the evidence lines of one
 * utterance — the text line, the phoneme line and the CTC template
 * posteriors — against the routed session vocabulary into one
 * {@link Decision} with a winning entry, a score and up to three
 * alternatives.
 *
 * <p>Fusion tiers (work order priority, mapped to the pre-v2 chain):
 * <ol>
 *   <li><strong>text EXACT</strong> — verbatim alias rules on trigger
 *       entries, and the lenient first-line TEXT rules (the pre-v2 idle L1
 *       gate; 首行即门 made its hits top-tier).</li>
 *   <li><strong>zipa EXACT</strong> — CTC posteriors at/above the effective
 *       forward threshold (first/last lines + triggers; middle lines carry
 *       no idle meaning), then the lenient first-line PHONETIC rule.</li>
 *   <li><strong>text NEAR</strong> — fuzzy alias similarity at/above the
 *       effective text threshold (trigger entries).</li>
 *   <li><strong>zipa NEAR</strong> — weighted phoneme similarity at/above
 *       the effective phoneme threshold (trigger templates).</li>
 *   <li><strong>AMBIGUOUS</strong> — the CTC margin rejected a top1 that
 *       would have passed its forward threshold (close runner-up).</li>
 *   <li><strong>REJECTED</strong> — nothing cleared anything.</li>
 * </ol>
 *
 * <p>Thresholds: per-entry {@link ThresholdHint} (game content pushed with
 * the vocabulary) overriding the {@link Calibration} engine defaults. A hint
 * component above 1.0 disables its tier for that entry — that is how the
 * pre-v2 reject levels (误触发治理) surface as data instead of consumer-side
 * branch logic.
 *
 * <p>Pure JVM (no Minecraft, no engine types) — unit-testable, and the
 * shared equivalence vectors (c1b_vectors.json) pin it against the pre-v2
 * chain's verdicts.
 */
public final class UtteranceAdjudicator {

    /** CTC margin evidence handed over by the ZIPA recognizer. */
    public record MarginInfo(String top1Id, float top1, float top2, boolean rejected) {}

    /** Which fusion tier produced a candidate (order = priority). */
    public enum Tier {
        TEXT_EXACT(0), CTC_EXACT(1), TEXT_NEAR(2), PHONEME_NEAR(3);
        final int rank;
        Tier(int rank) { this.rank = rank; }
    }

    /** Source of an EXACT candidate within its tier (CTC evidence before the
     *  lenient rules — the pre-v2 chain checked posteriors first). */
    public enum Source { VERBATIM, CTC, LENIENT, FUZZY }

    /** One fusion candidate. */
    public record Candidate(String pronId, String spellId, float score, Tier tier, Source source) {}

    /** The adjudication outcome: decision + candidates + diagnostics. */
    public record Adjudication(
            Decision decision,
            String spellId,
            String pronId,
            float score,
            List<Alternative> alternatives,
            RecognitionDiagnostics diagnostics
    ) {}

    private static final int MAX_ALTERNATIVES = 3;
    /** Hint components above this disable their tier for the entry. */
    private static final float DISABLED = 1.0f;

    private UtteranceAdjudicator() {}

    public static Adjudication adjudicate(Collection<SessionVocabulary.Entry> vocabulary, Calibration cal,
                                          String heardText, List<String> heardIpa,
                                          Map<String, Float> ctcPosteriors, MarginInfo margin) {
        cal = cal == null ? Calibration.DEFAULT : cal;
        List<String> ipa = heardIpa == null ? List.of() : heardIpa;
        String text = heardText == null ? "" : heardText;

        // Eligible surfaces: triggers + first/last chant lines (middle lines
        // carry no idle meaning — pre-v2 ChantGate rule, now structural).
        List<SessionVocabulary.Entry> triggers = new ArrayList<>();
        List<SessionVocabulary.Entry> firstLines = new ArrayList<>();
        List<SessionVocabulary.Entry> ctcSurface = new ArrayList<>();
        Map<String, SessionVocabulary.Entry> byId = new LinkedHashMap<>();
        for (SessionVocabulary.Entry e : vocabulary) {
            if (e == null || e.id() == null) continue;
            byId.put(e.id(), e);
            LineShapes.LineId line = LineShapes.parse(e.id());
            if (line == null) {
                triggers.add(e);
                ctcSurface.add(e);
            } else if (line.index() == 0) {
                firstLines.add(e);
                ctcSurface.add(e);
            } else {
                int max = LineShapes.maxLineIndex(vocabulary, line.spellId(), line.lang(), line.variant());
                if (line.index() == max) ctcSurface.add(e);
                // middle lines: CTC still scores them, but they are never candidates
            }
        }

        List<Candidate> candidates = new ArrayList<>();

        // ---- Tier 1: text EXACT (verbatim trigger aliases + lenient first lines)
        if (!text.isBlank()) {
            SpellMatcher.Result r = SpellMatcher.match(text, triggers);
            if (r.verbatim() != null
                    && r.verbatim().score() >= effectiveText(byId.get(r.verbatim().entryId()), cal)) {
                candidates.add(candidate(r.verbatim().entryId(), byId, r.verbatim().score(),
                        Tier.TEXT_EXACT, Source.VERBATIM));
            }
            for (SessionVocabulary.Entry line : firstLines) {
                if (effectiveText(line, cal) > DISABLED) continue; // reject-level overlay
                if (LenientLine.looseTextRulesHit(line.aliases(), text)) {
                    candidates.add(new Candidate(line.id(), line.spellId(), 1.0f,
                            Tier.TEXT_EXACT, Source.LENIENT));
                }
            }
        }

        // ---- Tier 2: zipa EXACT (CTC posteriors, then lenient first-line phonetic)
        if (ctcPosteriors != null && !ctcPosteriors.isEmpty()) {
            for (SessionVocabulary.Entry e : ctcSurface) {
                Float s = ctcPosteriors.get(e.id());
                if (s != null && s >= effectiveForward(e, cal)) {
                    candidates.add(new Candidate(e.id(), e.spellId(), s, Tier.CTC_EXACT, Source.CTC));
                }
            }
        }
        if (!ipa.isEmpty()) {
            String ipaText = String.join(" ", ipa);
            for (SessionVocabulary.Entry line : firstLines) {
                if (line.ipa().isEmpty() || effectivePhoneme(line, cal) > DISABLED) continue;
                for (String templ : line.ipa()) {
                    if (LenientLine.loosePhonetic(ipaText, templ)) {
                        candidates.add(new Candidate(line.id(), line.spellId(), 1.0f,
                                Tier.CTC_EXACT, Source.LENIENT));
                        break;
                    }
                }
            }
        }

        // ---- Tier 3: text NEAR (fuzzy alias similarity)
        if (!text.isBlank()) {
            SpellMatcher.Result r = SpellMatcher.match(text, triggers);
            if (r.fuzzy() != null
                    && r.fuzzy().score() >= effectiveText(byId.get(r.fuzzy().entryId()), cal)) {
                candidates.add(candidate(r.fuzzy().entryId(), byId, r.fuzzy().score(),
                        Tier.TEXT_NEAR, Source.FUZZY));
            }
        }

        // ---- Tier 4: zipa NEAR (weighted phoneme similarity)
        if (!ipa.isEmpty()) {
            PhonemeMatcher.Match pm = PhonemeMatcher.match(ipa, triggers);
            if (pm != null) {
                SessionVocabulary.Entry winner = byId.get(pm.entryId());
                if (winner != null && pm.score() >= effectivePhoneme(winner, cal)) {
                    candidates.add(new Candidate(pm.entryId(), winner.spellId(), pm.score(),
                            Tier.PHONEME_NEAR, Source.FUZZY));
                }
            }
        }

        // ---- Fusion: tier order, CTC-before-lenient within EXACT, then score, then id
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.tier().rank)
                .thenComparing(Comparator.comparingDouble(Candidate::score).reversed())
                .thenComparing(Candidate::pronId));

        String rejectionReason = null;
        Decision decision;
        Candidate winner = candidates.isEmpty() ? null : candidates.get(0);

        // ---- AMBIGUOUS: the margin rejected a would-be pass
        if (winner == null && margin != null && margin.rejected() && margin.top1Id() != null) {
            SessionVocabulary.Entry top1 = byId.get(margin.top1Id());
            float threshold = top1 != null ? effectiveForward(top1, cal) : cal.forward();
            if (margin.top1() >= threshold) {
                decision = Decision.AMBIGUOUS;
                rejectionReason = String.format(java.util.Locale.ROOT,
                        "ctc_margin: top1=%.4f top2=%.4f gap=%.4f < margin=%.4f (would have passed %.4f)",
                        margin.top1(), margin.top2(), margin.top1() - margin.top2(), cal.margin(), threshold);
                winner = new Candidate(margin.top1Id(), top1 != null ? top1.spellId() : margin.top1Id(),
                        margin.top1(), Tier.CTC_EXACT, Source.CTC);
            } else {
                decision = Decision.REJECTED;
                rejectionReason = "below_threshold";
            }
        } else if (winner == null) {
            decision = Decision.REJECTED;
            rejectionReason = text.isBlank() && ipa.isEmpty()
                    && (ctcPosteriors == null || ctcPosteriors.isEmpty())
                    ? "empty_utterance" : "no_vocabulary_hit";
        } else {
            decision = winner.tier() == Tier.TEXT_NEAR || winner.tier() == Tier.PHONEME_NEAR
                    ? Decision.NEAR : Decision.EXACT;
        }

        // Alternatives: the runner-ups behind the winner (top-k <= 3), one
        // entry per pronId (its best tier/score wins the slot).
        List<Alternative> alternatives = new ArrayList<>();
        if (winner != null) {
            List<String> seen = new ArrayList<>();
            seen.add(winner.pronId());
            for (Candidate c : candidates) {
                if (alternatives.size() >= MAX_ALTERNATIVES) break;
                if (seen.contains(c.pronId())) continue;
                seen.add(c.pronId());
                alternatives.add(new Alternative(c.spellId(), c.pronId(), c.score()));
            }
        }

        RecognitionDiagnostics diag = new RecognitionDiagnostics(
                winner != null ? winner.tier().name() : "NONE",
                rejectionReason,
                margin == null ? 0f : margin.top1(),
                margin == null ? 0f : margin.top2(),
                margin != null && margin.rejected(),
                ctcPosteriors == null ? Map.of() : Map.copyOf(ctcPosteriors));

        return new Adjudication(decision,
                winner == null ? "" : winner.spellId(),
                winner == null ? "" : winner.pronId(),
                winner == null ? 0f : winner.score(),
                List.copyOf(alternatives),
                diag);
    }

    // ------------------------------------------------------------- helpers

    private static Candidate candidate(String entryId, Map<String, SessionVocabulary.Entry> byId,
                                       float score, Tier tier, Source source) {
        SessionVocabulary.Entry e = byId.get(entryId);
        return new Candidate(entryId, e != null ? e.spellId() : entryId, score, tier, source);
    }

    private static float effectiveForward(SessionVocabulary.Entry e, Calibration cal) {
        ThresholdHint h = e == null ? null : e.threshold();
        return h == null || h.forward() == null ? cal.forward() : h.forward();
    }

    private static float effectivePhoneme(SessionVocabulary.Entry e, Calibration cal) {
        ThresholdHint h = e == null ? null : e.threshold();
        return h == null || h.phoneme() == null ? cal.phoneme() : h.phoneme();
    }

    private static float effectiveText(SessionVocabulary.Entry e, Calibration cal) {
        ThresholdHint h = e == null ? null : e.threshold();
        return h == null || h.text() == null ? cal.text() : h.text();
    }
}
