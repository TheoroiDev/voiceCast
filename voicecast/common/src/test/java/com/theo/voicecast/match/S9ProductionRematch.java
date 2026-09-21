package com.theo.voicecast.match;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.theo.voicecast.api.SessionVocabulary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * S9 production-semantics re-verification (issue #30 R-5; gated lab harness —
 * skipped unless {@code -Ds9prod.rematch=true} so normal builds are untouched).
 *
 * <p>Re-runs the S9 four-mode candidate-set comparison with the REAL
 * {@link SpellMatcher} (including the {@link Phonetics} phonetic layer the S9
 * python port lacked — the "语义代差" from s9_sherpa_report.md §4). Data:
 * cached engbench transcripts (raw.jsonl, 4 sherpa text engines) + the cached
 * negative-sample transcripts; no ASR is re-run (minute-scale cost).
 *
 * <p>Mode candidate sets (S9 D2 / D-15, as amended by the P30 supervisor
 * ruling): FULL = every spell's all-role aliases; OPEN = the full word list ∩
 * language bucket (same candidates as FULL — the trigger+release refinement
 * was reverted after the production-semantics re-verification showed the
 * Phonetics layer re-shuffles rather than removes false triggers); CONFIRM =
 * the target spell's all-role aliases; NARROW3 = target + top-3 M2E red/yellow
 * confusion neighbors. Roles are merged into ONE alias list per spell
 * (mirroring production: one Pronunciation per spell reaches the text
 * matcher), so the tie-break runs on the production spell-id domain.
 *
 * <p>Run: {@code gradlew :wizardreal-common:test --tests "*S9ProductionRematch*"
 * -Ds9prod.rematch=true}. Outputs: JSON + stdout summary under
 * {@code build/accent_calibration/} of the workspace root.
 */
class S9ProductionRematch {

    private static final List<String> LANGS = List.of("en", "zh", "ja");
    private static final String[] MODES = {"FULL", "OPEN", "CONFIRM", "NARROW3"};

    private final Map<String, Map<String, Map<String, List<String>>>> spells = new LinkedHashMap<>();
    private final Map<String, List<String>> neighbors = new LinkedHashMap<>();

    /** (lang, mode, group) -> {n, hit, cross} over positive rows. */
    private final Map<String, long[]> agg = new HashMap<>();
    /** (mode, kind) -> {n, hit, cross}. */
    private final Map<String, long[]> aggKind = new HashMap<>();
    /** engine -> {engbenchPass, engbenchPassHitByFull} sanity. */
    private final Map<String, long[]> sanity = new HashMap<>();

    private final Map<String, SpellMatcher.Match> matchCache = new HashMap<>();
    private final Map<String, List<SessionVocabulary.Entry>> candidateCache = new HashMap<>();

    @Test
    void s9ProductionRematch() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("s9prod.rematch"),
                "gated lab harness: run with -Ds9prod.rematch=true");
        String wsProp = System.getProperty("s9prod.ws");
        Path ws = (wsProp == null || wsProp.isBlank() ? Paths.get("../../..") : Paths.get(wsProp))
                .toAbsolutePath().normalize();
        Path rawJsonl = ws.resolve("wizardreal/tools/benchmark/out/engbench/20260907-010938/raw.jsonl");
        Path spellsDir = Paths.get("src/main/resources/data/wizardreal/voicecast/spells");
        Path redTsv = ws.resolve("ipa/out.confusion_empirical/red_yellow.tsv");
        Path negJson = ws.resolve("build/accent_calibration/s9_neg_transcripts.json");
        Path outJson = ws.resolve("build/accent_calibration/s9_production_rematch.json");

        loadSpells(spellsDir);
        loadNeighbors(redTsv);
        long t0 = System.currentTimeMillis();

        long rows = 0;
        long ipaRows = 0;
        try (Stream<String> lines = Files.lines(rawJsonl, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isBlank()) continue;
                JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                rows++;
                String text = r.has("text") && !r.get("text").isJsonNull() ? r.get("text").getAsString() : null;
                if (text == null) {
                    ipaRows++; // IPA CTC face (D1/D5): excluded, reconciliation only
                    continue;
                }
                String eng = r.get("engine").getAsString();
                String lang = r.get("lang").getAsString();
                String sid = r.get("spell").getAsString();
                String kind = r.get("kind").getAsString();
                String grp = kind.equals("alias") ? "trig" : "chant";
                for (String mode : MODES) {
                    SpellMatcher.Match m = match(mode, lang, sid, text);
                    boolean hit = m != null && m.entryId().equals(sid);
                    boolean cross = m != null && !m.entryId().equals(sid);
                    tally(agg, lang + "|" + mode + "|" + grp, hit, cross);
                    tally(aggKind, mode + "|" + kind, hit, cross);
                }
                long[] sv = sanity.computeIfAbsent(eng, k -> new long[2]);
                if (r.get("verdict").getAsString().equals("pass")) {
                    sv[0]++;
                    sv[1] += truthy(match("FULL", lang, sid, text), sid) ? 1 : 0;
                }
            }
        }

        // ---- negative-sample FPR (cached S9 transcripts, zipformer bilingual)
        List<String> negTexts = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(negJson, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("ok").getAsBoolean()) negTexts.add(o.get("text").getAsString());
        }
        Map<String, Object> negFpr = negativeFpr(negTexts);

        writeResults(outJson, rows, ipaRows, negTexts.size(), negFpr,
                System.currentTimeMillis() - t0);
        printReport(rows, ipaRows, negTexts.size(), negFpr);
    }

    // =====================================================================
    // matching (production SpellMatcher over mode candidate sets)
    // =====================================================================

    private SpellMatcher.Match match(String mode, String lang, String sid, String text) {
        String key = lang + "|" + (mode.equals("CONFIRM") || mode.equals("NARROW3") ? mode + "|" + sid : mode)
                + "|" + text;
        SpellMatcher.Match cached = matchCache.get(key);
        if (cached != null) return cached;
        SpellMatcher.Result r = SpellMatcher.match(text, candidates(mode, lang, sid));
        // Production pre-v2 semantics: the single text-matcher winner (the
        // best score across verbatim+fuzzy) decides.
        SpellMatcher.Match m = r.verbatim() != null && (r.fuzzy() == null || r.verbatim().score() >= r.fuzzy().score())
                ? r.verbatim() : r.fuzzy();
        matchCache.put(key, m);
        return m;
    }

    private List<SessionVocabulary.Entry> candidates(String mode, String lang, String sid) {
        String key = mode + "|" + lang + "|" + sid;
        return candidateCache.computeIfAbsent(key, k -> {
            List<SessionVocabulary.Entry> out = new ArrayList<>();
            for (Map.Entry<String, Map<String, Map<String, List<String>>>> e : spells.entrySet()) {
                boolean declared = e.getKey().equals(sid);
                boolean neighbor = mode.equals("NARROW3") && neighbors(sid).contains(e.getKey());
                if (mode.equals("CONFIRM") || mode.equals("NARROW3")) {
                    if (!declared && !neighbor) continue;
                }
                List<String> aliases = aliasesFor(e.getValue(), lang,
                        // OPEN reverted to the full word list per language bucket
                        // (supervisor ruling on the P30 re-verification) — same
                        // roles as FULL; only CONFIRM/NARROW3 narrow the roster.
                        List.of("trig", "cast", "chant"));
                if (aliases.isEmpty()) continue;
                out.add(spell(e.getKey(), aliases));
            }
            return out;
        });
    }

    /** Merged raw aliases of one spell for one language in role order
     *  (trigger, cast, chant trigger, body variants) — production feeds one
     *  alias list per spell to the text matcher. */
    private static List<String> aliasesFor(Map<String, Map<String, List<String>>> perLang,
                                           String lang, List<String> roles) {
        Map<String, List<String>> roles0 = perLang.get(lang);
        if (roles0 == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String role : roles0.keySet()) { // trig, cast, chant (insertion order)
            if (!roles.contains(role)) continue;
            out.addAll(roles0.get(role));
        }
        return out;
    }

    private List<String> neighbors(String sid) {
        return neighbors.getOrDefault(sid, List.of());
    }

    private static SessionVocabulary.Entry spell(String id, List<String> aliases) {
        return new SessionVocabulary.Entry(id, List.of(), aliases, null, null);
    }

    // =====================================================================
    // data loading
    // =====================================================================

    private void loadSpells(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonObject d = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                String sid = d.get("id").getAsString();
                Map<String, Map<String, List<String>>> perLang = new LinkedHashMap<>();
                for (String lang : LANGS) {
                    Map<String, List<String>> roles = new LinkedHashMap<>();
                    JsonObject trigger = d.getAsJsonObject("trigger");
                    roles.put("trig", aliases(trigger == null ? null : trigger
                            .getAsJsonObject("languages").get(lang)));
                    JsonObject chants = d.getAsJsonObject("chants");
                    if (chants != null && chants.has("languages")) {
                        JsonObject lc = chants.getAsJsonObject("languages").getAsJsonObject(lang);
                        if (lc != null) {
                            roles.put("cast", aliases(lc.getAsJsonObject("cast")));
                            JsonObject trigLine = lc.getAsJsonObject("trigger");
                            roles.put("chant", new ArrayList<>());
                            if (trigLine != null) roles.get("chant").addAll(aliasesOf(trigLine));
                            if (lc.has("body")) {
                                for (JsonElement variant : lc.getAsJsonArray("body")) {
                                    for (JsonElement line : variant.getAsJsonArray()) {
                                        roles.get("chant").addAll(aliasesOf(line.getAsJsonObject()));
                                    }
                                }
                            }
                        }
                    }
                    roles.values().removeIf(List::isEmpty);
                    if (!roles.isEmpty()) perLang.put(lang, roles);
                }
                spells.put(sid, perLang);
            }
        }
    }

    /** aliases of an object shaped like LineMeta ({@code {"aliases": [...]}}),
     *  or of a plain language-bucket array. */
    private static List<String> aliases(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (JsonElement a : e.getAsJsonArray()) out.add(a.getAsString());
            return out;
        }
        return aliasesOf(e.getAsJsonObject());
    }

    private static List<String> aliasesOf(JsonObject o) {
        List<String> out = new ArrayList<>();
        if (o != null && o.has("aliases")) {
            for (JsonElement a : o.getAsJsonArray("aliases")) out.add(a.getAsString());
        }
        return out;
    }

    /** M2E red/yellow ledger -> per source: judged ids, rate desc / judged id
     *  asc, top-3 (same rule as the S9 harness and confusion_neighbors.tsv). */
    private void loadNeighbors(Path tsv) throws IOException {
        Map<String, List<Object[]>> pairs = new LinkedHashMap<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t");
            pairs.computeIfAbsent(c[1], k -> new ArrayList<>())
                    .add(new Object[]{Double.parseDouble(c[3].replace("%", "")), c[2]});
        }
        for (Map.Entry<String, List<Object[]>> e : pairs.entrySet()) {
            e.getValue().sort((a, b) -> {
                int byRate = Double.compare((double) b[0], (double) a[0]);
                return byRate != 0 ? byRate : ((String) a[1]).compareTo((String) b[1]);
            });
            List<String> top3 = new ArrayList<>();
            for (Object[] p : e.getValue().subList(0, Math.min(3, e.getValue().size()))) top3.add((String) p[1]);
            neighbors.put(e.getKey(), top3);
        }
    }

    // =====================================================================
    // negative-sample FPR
    // =====================================================================

    private Map<String, Object> negativeFpr(List<String> negTexts) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String bucket : List.of("zh", "en", "zh+en")) {
            List<String> langs = bucket.equals("zh+en") ? List.of("zh", "en") : List.of(bucket);
            // FULL / OPEN: joint trigger (any spell fires)
            for (String mode : List.of("FULL", "OPEN")) {
                long fires = 0;
                for (String text : negTexts) {
                    boolean fire = false;
                    for (String lang : langs) {
                        if (match(mode, lang, "-", text) != null) fire = true;
                    }
                    fires += fire ? 1 : 0;
                }
                out.put(bucket + "|" + mode, pct(fires, negTexts.size()));
            }
            // CONFIRM / NARROW3: per-spell (75 targets), any-fire + target-win
            for (String mode : List.of("CONFIRM", "NARROW3")) {
                List<Double> anyFire = new ArrayList<>();
                List<Double> targetWin = new ArrayList<>();
                for (String sid : spells.keySet()) {
                    long fire = 0;
                    long win = 0;
                    for (String text : negTexts) {
                        boolean fired = false;
                        boolean won = false;
                        for (String lang : langs) {
                            SpellMatcher.Match m = match(mode, lang, sid, text);
                            if (m != null) {
                                fired = true;
                                if (m.entryId().equals(sid)) won = true;
                            }
                        }
                        fire += fired ? 1 : 0;
                        win += won ? 1 : 0;
                    }
                    anyFire.add(pct(fire, negTexts.size()));
                    targetWin.add(pct(win, negTexts.size()));
                }
                out.put(bucket + "|" + mode + "|any", stats(anyFire));
                out.put(bucket + "|" + mode + "|target", stats(targetWin));
            }
        }
        return out;
    }

    private static Map<String, Object> stats(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("max", sorted.get(sorted.size() - 1));
        s.put("median", sorted.get(sorted.size() / 2));
        s.put("p90", sorted.get((int) Math.ceil(sorted.size() * 0.9) - 1));
        return s;
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }

    private static boolean truthy(SpellMatcher.Match m, String sid) {
        return m != null && m.entryId().equals(sid);
    }

    private static void tally(Map<String, long[]> agg, String key, boolean hit, boolean cross) {
        long[] t = agg.computeIfAbsent(key, k -> new long[3]);
        t[0]++;
        t[1] += hit ? 1 : 0;
        t[2] += cross ? 1 : 0;
    }

    // =====================================================================
    // output
    // =====================================================================

    private void writeResults(Path out, long rows, long ipaRows, int negCount,
                              Map<String, Object> negFpr, long ms) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("semantics", "production SpellMatcher (orthographic + Phonetics layer, tie-break spell id)");
        result.put("rows_total", rows);
        result.put("ipa_rows_excluded", ipaRows);
        result.put("text_rows", rows - ipaRows);
        result.put("negatives", negCount);
        result.put("elapsed_ms", ms);
        Map<String, Object> tables = new TreeMap<>();
        for (Map.Entry<String, long[]> e : new TreeMap<>(agg).entrySet()) {
            long[] t = e.getValue();
            tables.put(e.getKey(), Map.of("n", t[0], "hit%", pct(t[1], t[0]), "cross%", pct(t[2], t[0])));
        }
        for (Map.Entry<String, long[]> e : new TreeMap<>(aggKind).entrySet()) {
            long[] t = e.getValue();
            tables.put("kind|" + e.getKey(), Map.of("n", t[0], "hit%", pct(t[1], t[0]), "cross%", pct(t[2], t[0])));
        }
        result.put("tables", tables);
        result.put("negative_fpr", negFpr);
        Files.writeString(out, new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(result), StandardCharsets.UTF_8);
    }

    private void printReport(long rows, long ipaRows, int negCount, Map<String, Object> negFpr) {
        System.out.println("=== S9 production-semantics rematch (real SpellMatcher incl. Phonetics) ===");
        System.out.println("rows=" + rows + " ipaExcluded=" + ipaRows + " text=" + (rows - ipaRows)
                + " negatives=" + negCount);
        for (String lang : LANGS) {
            for (String grp : List.of("trig", "chant")) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%s %s:", lang, grp));
                for (String mode : MODES) {
                    long[] t = agg.get(lang + "|" + mode + "|" + grp);
                    line.append(String.format(Locale.ROOT, "  %s hit=%.2f%% cross=%.2f%% (n=%d)",
                            mode, pct(t[1], t[0]), pct(t[2], t[0]), t[0]));
                }
                System.out.println(line);
            }
        }
        // D5 gate legs
        long[] zhTrigFull = agg.get("zh|FULL|trig");
        long[] zhTrigOpen = agg.get("zh|OPEN|trig");
        long crossFull = 0;
        long crossOpen = 0;
        for (String lang : LANGS) {
            for (String grp : List.of("trig", "chant")) {
                crossFull += agg.get(lang + "|FULL|" + grp)[2];
                crossOpen += agg.get(lang + "|OPEN|" + grp)[2];
            }
        }
        double leg1 = pct(zhTrigOpen[1], zhTrigOpen[0]) - pct(zhTrigFull[1], zhTrigFull[0]);
        double leg2 = crossFull == 0 ? 0 : 100.0 * (crossFull - crossOpen) / crossFull;
        System.out.printf(Locale.ROOT,
                "D5 leg1 zh trigger hit FULL %.2f%% -> OPEN %.2f%% (delta %+.2fpp, gate >= -2pp)%n",
                pct(zhTrigFull[1], zhTrigFull[0]), pct(zhTrigOpen[1], zhTrigOpen[0]), leg1);
        System.out.printf(Locale.ROOT, "D5 leg2 cross %d -> %d (-%.1f%%, gate >= 30%%)%n",
                crossFull, crossOpen, leg2);
        System.out.println("negative FPR: " + negFpr);
    }
}
