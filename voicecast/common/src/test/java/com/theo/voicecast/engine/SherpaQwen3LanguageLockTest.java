package com.theo.voicecast.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Language-lock resolution for the qwen3 decode constraint (voiceCast#49):
 * auto locks only single mapped buckets, off never locks, anything else is a
 * verbatim native language name. Values are the setOption spellings probed in
 * docs/plans/qwen3_language_constraint.md §3.1 (ISO codes are NOT valid).
 */
class SherpaQwen3LanguageLockTest {

    private static String resolve(String config, List<String> languages) {
        return SherpaQwen3Recognizer.resolveLanguageLock(config, languages);
    }

    @Test
    void auto_singleMappedBucket_locks() {
        assertEquals("English", resolve("auto", List.of("en")));
        assertEquals("Chinese", resolve("auto", List.of("zh")));
        assertEquals("Japanese", resolve("auto", List.of("ja")));
        assertEquals("Cantonese", resolve("auto", List.of("yue")));
    }

    @Test
    void auto_bucketsCaseNormalized() {
        assertEquals("Japanese", resolve("auto", List.of("JA")));
        assertEquals("Russian", resolve("auto", List.of(" Ru ")));
    }

    @Test
    void auto_multiBucket_staysOpen() {
        assertNull(resolve("auto", List.of("en", "zh")));
    }

    @Test
    void auto_unmappedBucket_staysOpen() {
        assertNull(resolve("auto", List.of("xx")));
    }

    @Test
    void auto_emptyLanguages_staysOpen() {
        assertNull(resolve("auto", List.of()));
    }

    @Test
    void off_neverLocks() {
        assertNull(resolve("off", List.of("en")));
        assertNull(resolve("OFF", List.of("en")));
    }

    @Test
    void fixedValue_passesThroughVerbatim() {
        assertEquals("English", resolve("English", List.of("zh")));
        assertEquals("Klingon", resolve("Klingon", List.of("en")));
    }

    @Test
    void blankOrNullConfig_meansAuto() {
        assertEquals("English", resolve("", List.of("en")));
        assertEquals("Chinese", resolve(null, List.of("zh")));
    }
}
