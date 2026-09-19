package com.theo.voicecast.server;

import com.theo.voicecast.VoiceCast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GRAY_NARROW confusion-neighbor lists ({@code
 * assets/voicecast/confusion_neighbors.tsv}, issue #30 D4): per source spell,
 * the spells it is most often misjudged as (M2E red/yellow ledger, rate
 * descending, judged id ascending, top-3 baked in at assetization time —
 * K=3 per the D-15 gray-narrow definition).
 *
 * <p>Loaded once from the classpath (the file ships in this jar); a missing
 * or malformed file degrades to "no neighbors" — {@link
 * VocabularyRouter#forSpells} then narrows to the declared spell only, never
 * wider than declared. Pure and unit-testable: no Minecraft types.
 */
final class ConfusionNeighbors {
    private static final String RESOURCE = "/assets/voicecast/confusion_neighbors.tsv";

    private ConfusionNeighbors() {}

    private static volatile Map<String, List<String>> table;

    /** Top confusion neighbors of one spell (empty when unknown). */
    static List<String> neighbors(String spellId) {
        Map<String, List<String>> t = table();
        List<String> out = t.get(spellId);
        return out == null ? List.of() : out;
    }

    private static Map<String, List<String>> table() {
        Map<String, List<String>> t = table;
        if (t == null) {
            synchronized (ConfusionNeighbors.class) {
                t = table;
                if (t == null) {
                    t = load();
                    table = t;
                }
            }
        }
        return t;
    }

    private static Map<String, List<String>> load() {
        Map<String, List<String>> out = new HashMap<>();
        try (InputStream in = ConfusionNeighbors.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                VoiceCast.LOGGER.warn("{} missing - GRAY_NARROW narrows to the declared spell only", RESOURCE);
                return Map.of();
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] cols = line.split("\t");
                if (cols.length < 2) continue;
                List<String> neighbors = new ArrayList<>(cols.length - 1);
                for (int i = 1; i < cols.length; i++) {
                    if (!cols[i].isBlank()) neighbors.add(cols[i].trim());
                }
                if (!neighbors.isEmpty()) out.put(cols[0].trim(), List.copyOf(neighbors));
            }
        } catch (Exception e) {
            VoiceCast.LOGGER.warn("Failed to load {} - GRAY_NARROW narrows to the declared spell only", RESOURCE, e);
            return Map.of();
        }
        return Map.copyOf(out);
    }
}
