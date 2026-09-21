package com.theo.voicecast.engine;

import com.theo.voicecast.api.Pronunciation;
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

    @Test
    void triggerAliasesOnly() {
        List<String> hotwords = SherpaQwen3Recognizer.extractHotwords(List.of(
                new Pronunciation("fulmen", List.of(), List.of("fulmen", "lightning")),
                new Pronunciation("fulmen.chant.en.0:0", List.of(), List.of("rods from the sky")),
                new Pronunciation("aegis", List.of(), List.of("aegis"))));
        assertEquals(List.of("fulmen", "lightning", "aegis"), hotwords);
    }

    @Test
    void dedupesAndSkipsBlank() {
        List<String> hotwords = SherpaQwen3Recognizer.extractHotwords(List.of(
                new Pronunciation("fulmen", List.of(), List.of("fulmen", " fulmen ", "")),
                new Pronunciation("aegis", List.of(), List.of("aegis", " "))));
        assertEquals(List.of("fulmen", "aegis"), hotwords);
    }

    @Test
    void capsAt100Entries() {
        var vocab = new java.util.ArrayList<Pronunciation>();
        for (int i = 0; i < 150; i++) {
            vocab.add(new Pronunciation("spell" + i, List.of(), List.of("alias" + i)));
        }
        List<String> hotwords = SherpaQwen3Recognizer.extractHotwords(vocab);
        assertEquals(100, hotwords.size());
        assertEquals("alias0", hotwords.get(0));
        assertEquals("alias99", hotwords.get(99));
    }

    @Test
    void emptyAndNullVocabulariesYieldNoHotwords() {
        assertTrue(SherpaQwen3Recognizer.extractHotwords(List.of()).isEmpty());
        assertTrue(SherpaQwen3Recognizer.extractHotwords(null).isEmpty());
        assertTrue(SherpaQwen3Recognizer.hotwordsCsv(List.of()).isEmpty());
    }

    @Test
    void csvIsCommaJoined() {
        assertEquals("aegis, fulmen", SherpaQwen3Recognizer.hotwordsCsv(List.of("aegis", "fulmen")));
    }
}
