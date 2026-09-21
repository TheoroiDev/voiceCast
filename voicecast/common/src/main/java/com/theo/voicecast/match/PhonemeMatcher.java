package com.theo.voicecast.match;

import com.theo.voicecast.api.SessionVocabulary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches recognized IPA phoneme sequences against the {@code ipa()} templates
 * of vocabulary entries (semantic contract v2: moved from WizardReal's
 * matcher chain into the voicecast adjudicator, work order C1b §0.2).
 *
 * <p>Both sides are normalized via the internal {@link IpaText}: diacritics/
 * stress marks are stripped, and the IPA string is split into per-phoneme
 * tokens (multi-char affricates such as tʃ/dʒ are kept together). The
 * per-entry threshold ({@link com.theo.voicecast.api.ThresholdHint#phoneme()}
 * or the calibration default) is applied by the adjudicator, not here.
 *
 * <p>S6-MATCHER/S6-FINAL semantics unchanged (issue #29): weighted edit
 * distance with the data-driven confusion table
 * {@code assets/voicecast/phoneme_costs.tsv} (M2E E-3 empirical matrix,
 * 891 pairs, cost = normalized -ln p(heard|target); D2), scaled by
 * {@link #COST_SCALE} = 2.0 at match time and clamped back into the D2 range
 * [0.1, 1]; insertions/deletions cost a flat {@link #INDEL_COST} = 0.6;
 * out-of-table substitutions stay at {@link #DEFAULT_SUB_COST} = 1.0; the
 * exact-template contiguous-chunk bonus (0.92) is unchanged.
 */
public final class PhonemeMatcher {

    /**
     * Insertion/deletion cost (D3): 0.6 x the substitution baseline. An
     * indel has no (heard,target) pair to look up, so the baseline is always
     * the out-of-table {@link #DEFAULT_SUB_COST} — i.e. a flat 0.6. Both
     * directions share it. NOT scaled.
     */
    public static final float INDEL_COST = 0.6f;

    /** Out-of-table substitution cost (D2: 平权 1.0). Not scaled. */
    public static final float DEFAULT_SUB_COST = 1.0f;

    /** Global in-table cost scale (S6-FINAL D1, locked): every cost looked
     *  up from the confusion table is multiplied by this at match time and
     *  clamped back into [0.1, 1]. */
    public static final float COST_SCALE = 2.0f;

    /** D2 value-range floor for scaled in-table costs (inactive at 2.0x). */
    public static final float SCALED_COST_MIN = 0.1f;

    /** D2 value-range ceiling for scaled in-table costs. */
    public static final float SCALED_COST_MAX = 1.0f;

    /** Jar asset holding the confusion cost table (D2). */
    static final String COST_TABLE_RESOURCE = "/assets/voicecast/phoneme_costs.tsv";

    private static volatile Map<String, Float> costs;

    private PhonemeMatcher() {}

    public record Match(String entryId, float score) {}

    /** Best template match over the given (trigger-surface) entries; null
     *  when the heard sequence is empty or no entry has templates. */
    public static Match match(List<String> heardIpa, List<SessionVocabulary.Entry> entries) {
        if (heardIpa == null || heardIpa.isEmpty()) return null;
        List<String> heard = IpaText.normalizeTokens(heardIpa);
        if (heard.isEmpty()) return null;

        String bestId = null;
        float bestScore = 0f;
        for (SessionVocabulary.Entry entry : entries) {
            for (String template : entry.ipa()) {
                List<String> target = IpaText.tokenize(template);
                if (target.isEmpty()) continue;
                float score = score(heard, target);
                if (score > bestScore) {
                    bestScore = score;
                    bestId = entry.id();
                }
            }
        }
        if (bestId == null) return null;
        return new Match(bestId, bestScore);
    }

    private static float score(List<String> heard, List<String> target) {
        float sim = similarity(heard, target);
        if (containsSublist(heard, target)) {
            // exact template spoken as a contiguous chunk -> very strong match
            sim = Math.max(sim, 0.92f);
        }
        return sim;
    }

    /**
     * Weighted edit-distance similarity (S6 WO D2/D3/D4 + S6-FINAL D1).
     * Substitution of heard token h against target token t costs
     * {@code clamp(COST_SCALE * cost(h,t), [0.1, 1])} from the confusion
     * table (flat 1.0 when out of table, 0 on identity); insertions and
     * deletions cost {@link #INDEL_COST}. Normalized as
     * {@code 1 - cost / max(len)}. Tokens must already be normalized (both
     * sides go through {@link IpaText}, which is also how the table was
     * built).
     */
    static float similarity(List<?> heard, List<?> target) {
        int m = heard.size();
        int n = target.size();
        int maxLen = Math.max(m, n);
        if (maxLen == 0) return 1f;
        // two-row DP over the weighted costs
        float[] prev = new float[n + 1];
        float[] cur = new float[n + 1];
        for (int j = 1; j <= n; j++) prev[j] = prev[j - 1] + INDEL_COST; // heard empty: swallow target
        for (int i = 1; i <= m; i++) {
            cur[0] = prev[0] + INDEL_COST; // extra heard token vs empty target prefix
            Object h = heard.get(i - 1);
            for (int j = 1; j <= n; j++) {
                float sub = prev[j - 1] + (h.equals(target.get(j - 1))
                        ? 0f : subCost(h, target.get(j - 1)));
                float ins = cur[j - 1] + INDEL_COST; // heard has extra token
                float del = prev[j] + INDEL_COST;    // target phoneme swallowed
                cur[j] = Math.min(sub, Math.min(ins, del));
            }
            float[] tmp = prev; prev = cur; cur = tmp;
        }
        return 1f - prev[n] / maxLen;
    }

    /**
     * Table cost for substituting heard token h against template token t:
     * {@code clamp(raw * COST_SCALE, [0.1, 1])} (S6-FINAL D1). Out-of-table
     * pairs keep the unscaled flat {@link #DEFAULT_SUB_COST}.
     */
    static float subCost(Object heard, Object target) {
        Float c = costTable().get(heard + "\t" + target);
        if (c == null) return DEFAULT_SUB_COST;
        return Math.max(SCALED_COST_MIN, Math.min(SCALED_COST_MAX, c * COST_SCALE));
    }

    /**
     * Lazily loaded confusion cost table, keyed "heard\ttarget". Loaded once
     * per JVM; the file is a tracked jar asset so a missing file is a hard
     * error (v0 hard cut — no equal-weight fallback, D2).
     */
    private static Map<String, Float> costTable() {
        Map<String, Float> t = costs;
        if (t == null) {
            synchronized (PhonemeMatcher.class) {
                if (costs == null) costs = loadCosts();
                t = costs;
            }
        }
        return t;
    }

    private static Map<String, Float> loadCosts() {
        try (InputStream in = PhonemeMatcher.class.getResourceAsStream(COST_TABLE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("phoneme cost table not found on classpath: "
                        + COST_TABLE_RESOURCE + "; S6 hard cut: no equal-weight fallback");
            }
            Map<String, Float> parsed = parseCosts(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
            if (parsed.isEmpty()) {
                throw new IllegalStateException("phoneme cost table is empty: " + COST_TABLE_RESOURCE);
            }
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("failed to load phoneme cost table " + COST_TABLE_RESOURCE, e);
        }
    }

    /** Table parser (package-private for the regression tests): skips blank
     *  and {@code #} lines; each data line is {@code heard \t target \t cost}. */
    static Map<String, Float> parseCosts(List<String> lines) throws IOException {
        Map<String, Float> out = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t");
            if (c.length < 3) throw new IOException("bad cost line (need 3 columns): " + line);
            out.put(c[0] + "\t" + c[1], Float.parseFloat(c[2].trim()));
        }
        return out;
    }

    /** Test hook: direct access to the loaded table (keys "heard\ttarget"). */
    static Map<String, Float> tableForTest() {
        return costTable();
    }

    private static boolean containsSublist(List<?> hay, List<?> needle) {
        if (needle.isEmpty() || hay.size() < needle.size()) return false;
        outer:
        for (int start = 0; start <= hay.size() - needle.size(); start++) {
            for (int j = 0; j < needle.size(); j++) {
                if (!hay.get(start + j).equals(needle.get(j))) continue outer;
            }
            return true;
        }
        return false;
    }
}
