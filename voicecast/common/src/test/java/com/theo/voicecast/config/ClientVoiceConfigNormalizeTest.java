package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientVoiceConfigNormalizeTest {

    @Test
    void voskAliasesNormalizeToCanonicalId() {
        // Legacy vosk-text (and its aliases) now normalize to vosk-en.
        for (String alias : new String[]{"vosk", "text", "vosk-text", "word",
                "en-us", "en", "english", "vosk-en", "vosk-en-us"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void ipaAliasesNormalizeToCanonicalId() {
        for (String alias : new String[]{"ipa", "phoneme", "phonemes", "ipa-phonemes"}) {
            assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void cjkAliasesNormalizeToCanonicalId() {
        for (String alias : new String[]{"zh", "zh-cn", "chinese", "中文", "vosk-cn"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK_CN, ClientVoiceConfig.normalize(alias), alias);
        }
        for (String alias : new String[]{"ja", "ja-jp", "japanese", "日本語", "vosk-jp"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK_JP, ClientVoiceConfig.normalize(alias), alias);
        }
        for (String alias : new String[]{"ko", "ko-kr", "korean", "한국어", "vosk-kr"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK_KR, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    /** Pre-rename ids must migrate to the renamed ids (saved configs keep working). */
    @Test
    void legacyIdsMigrateToRenamedIds() {
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("vosk-en-us"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_CN, ClientVoiceConfig.normalize("vosk-zh-cn"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_JP, ClientVoiceConfig.normalize("vosk-ja-jp"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_KR, ClientVoiceConfig.normalize("vosk-ko-kr"));
        // Case-insensitive migration too.
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("VOSK-EN-US"));
    }

    @Test
    void renamedIdsAreIdempotent() {
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("vosk-en"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_CN, ClientVoiceConfig.normalize("vosk-cn"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_JP, ClientVoiceConfig.normalize("vosk-jp"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_KR, ClientVoiceConfig.normalize("vosk-kr"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("VOSK"));
        assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize("IPA-Phonemes"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_CN, ClientVoiceConfig.normalize("ZH-CN"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_KR, ClientVoiceConfig.normalize("Korean"));
    }

    @Test
    void unknownIsNull() {
        assertNull(ClientVoiceConfig.normalize("whisper"));
        assertNull(ClientVoiceConfig.normalize(""));
        assertNull(ClientVoiceConfig.normalize(null));
    }

    @Test
    void normalizedValuesAreValidEngines() {
        for (String alias : new String[]{"vosk", "ipa", "en", "english", "word",
                "zh", "chinese", "ja", "japanese", "ko", "korean",
                "vosk-en", "vosk-cn", "vosk-jp", "vosk-kr",
                "vosk-en-us", "vosk-zh-cn", "vosk-ja-jp", "vosk-ko-kr"}) {
            String norm = ClientVoiceConfig.normalize(alias);
            assertTrue(ClientVoiceConfig.isValidEngine(norm), alias + " -> " + norm);
        }
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_EN));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_CN));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_JP));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_KR));
        assertFalse(ClientVoiceConfig.isValidEngine("vosk-ru-ru"));
        assertFalse(ClientVoiceConfig.isValidEngine("vosk-zh"));
    }
}
