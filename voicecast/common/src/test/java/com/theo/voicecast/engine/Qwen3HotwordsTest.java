package com.theo.voicecast.engine;

import com.theo.voicecast.api.SessionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hotword-subset extraction for {@link SherpaQwen3Recognizer} (G-QWEN3
 * mandatory condition ①): hotwords are the session vocabulary's TRIGGER
 * aliases (no {@code .chant.} marker) in the already language-routed
 * projection, deduplicated, capped at 100 entries.
 */
class Qwen3HotwordsTest {

    private static SessionVocabulary.Entry entry(String id, String... aliases) {
        return new SessionVocabulary.Entry(id, List.of(), List.of(aliases), null, null);
    }

    @Test
    void triggerAliasesOnly() {
        List<String> hotwords = SherpaQwen3Recognizer.extractHotwords(new SessionVocabulary(List.of(
                entry("fulmen", "fulmen", "lightning"),
                entry("fulmen.chant.en.0:0", "rods from the sky"),
                entry("aegis", "aegis"))));
        assertEquals(List.of("fulmen", "lightning", "aegis"), hotwords);
    }

    @Test
    void dedupesAndSkipsBlank() {
        List<String> hotwords = SherpaQwen3Recognizer.extractHotwords(new SessionVocabulary(List.of(
                entry("fulmen", "fulmen", " fulmen ", ""),
                entry("aegis", "aegis", " "))));
        assertEquals(List.of("fulmen", "aegis"), hotwords);
    }

    @Test
    void capsAt100Entries() {
        var vocab = new java.util.ArrayList<SessionVocabulary.Entry>();
        for (int i = 0; i < 150; i++) {
            vocab.add(entry("spell" + i, "alias" + i));
        }
        List<String> hotwords = SherpaQwen3Recognizer.extractHotwords(new SessionVocabulary(vocab));
        assertEquals(100, hotwords.size());
        assertEquals("alias0", hotwords.get(0));
        assertEquals("alias99", hotwords.get(99));
    }

    @Test
    void emptyAndNullVocabulariesYieldNoHotwords() {
        assertTrue(SherpaQwen3Recognizer.extractHotwords(SessionVocabulary.EMPTY).isEmpty());
        assertTrue(SherpaQwen3Recognizer.extractHotwords(null).isEmpty());
        assertTrue(SherpaQwen3Recognizer.hotwordsCsv(List.of()).isEmpty());
    }

    @Test
    void csvIsCommaJoined() {
        assertEquals("aegis, fulmen", SherpaQwen3Recognizer.hotwordsCsv(List.of("aegis", "fulmen")));
    }
}
