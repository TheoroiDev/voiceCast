package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Disabled("TODO(#42): update to sherpa ids after vosk removal")
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
        for (String alias : new String[]{"zh", "zh-cn", "cn", "chinese", "中文", "vosk-zh"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK_ZH, ClientVoiceConfig.normalize(alias), alias);
        }
        for (String alias : new String[]{"ja", "ja-jp", "jp", "japanese", "日本語", "vosk-ja"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK_JA, ClientVoiceConfig.normalize(alias), alias);
        }
        for (String alias : new String[]{"ko", "ko-kr", "kr", "korean", "한국어", "vosk-ko"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK_KO, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    /** Pre-rename ids must migrate to the renamed ids (saved configs keep working). */
    @Test
    void legacyIdsMigrateToRenamedIds() {
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("vosk-en-us"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_ZH, ClientVoiceConfig.normalize("vosk-zh-cn"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_JA, ClientVoiceConfig.normalize("vosk-ja-jp"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_KO, ClientVoiceConfig.normalize("vosk-ko-kr"));
        // 0.3.x engine ids (old cn/jp/kr codes) migrate to the two-letter codes.
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_ZH, ClientVoiceConfig.normalize("vosk-cn"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_JA, ClientVoiceConfig.normalize("vosk-jp"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_KO, ClientVoiceConfig.normalize("vosk-kr"));
        // Case-insensitive migration too.
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("VOSK-EN-US"));
    }

    @Test
    void twoLetterCodeIdsAreIdempotent() {
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("vosk-en"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_ZH, ClientVoiceConfig.normalize("vosk-zh"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_JA, ClientVoiceConfig.normalize("vosk-ja"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_KO, ClientVoiceConfig.normalize("vosk-ko"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_EN, ClientVoiceConfig.normalize("VOSK"));
        assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize("IPA-Phonemes"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_ZH, ClientVoiceConfig.normalize("ZH-CN"));
        assertEquals(ClientVoiceConfig.ENGINE_VOSK_KO, ClientVoiceConfig.normalize("Korean"));
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
                "vosk-en", "vosk-zh", "vosk-ja", "vosk-ko",
                "vosk-en-us", "vosk-zh-cn", "vosk-ja-jp", "vosk-ko-kr",
                "vosk-cn", "vosk-jp", "vosk-kr"}) {
            String norm = ClientVoiceConfig.normalize(alias);
            assertTrue(ClientVoiceConfig.isValidEngine(norm), alias + " -> " + norm);
        }
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_EN));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_ZH));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_JA));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_VOSK_KO));
        assertFalse(ClientVoiceConfig.isValidEngine("vosk-ru-ru"));
        assertFalse(ClientVoiceConfig.isValidEngine("vosk-cnn"));
    }
}
