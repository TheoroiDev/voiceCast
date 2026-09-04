package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.4.0: vosk removed, all vosk-family ids normalize to sherpa equivalents. */
class ClientVoiceConfigNormalizeTest {

    @Test
    void englishAliasesNormalizeToSherpaZhEn() {
        for (String alias : new String[]{"vosk", "text", "vosk-text", "word",
                "en-us", "en", "english", "vosk-en", "vosk-en-us"}) {
            assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void ipaAliasesNormalizeToCanonicalId() {
        for (String alias : new String[]{"ipa", "phoneme", "phonemes", "ipa-phonemes"}) {
            assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void chineseAliasesNormalizeToSherpaZhEn() {
        for (String alias : new String[]{"zh", "zh-cn", "cn", "chinese", "中文", "vosk-zh", "vosk-cn", "vosk-zh-cn"}) {
            assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void japaneseAliasesNormalizeToSherpaSensevoice() {
        for (String alias : new String[]{"ja", "ja-jp", "jp", "japanese", "日本語", "vosk-ja", "vosk-jp", "vosk-ja-jp"}) {
            assertEquals(ClientVoiceConfig.ENGINE_SHERPA_SENSEVOICE, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void koreanAliasesNormalizeToSherpaSensevoice() {
        for (String alias : new String[]{"ko", "ko-kr", "kr", "korean", "한국어", "vosk-ko", "vosk-kr", "vosk-ko-kr"}) {
            assertEquals(ClientVoiceConfig.ENGINE_SHERPA_SENSEVOICE, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void legacyIdsMigrateToSherpaIds() {
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize("vosk-en-us"));
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize("VOSK-EN-US"));
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize("VOSK"));
    }

    @Test
    void sherpaIdsAreIdempotent() {
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize("sherpa-zh-en"));
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_SENSEVOICE, ClientVoiceConfig.normalize("sherpa-sensevoice"));
        assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize("ipa-phonemes"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize("VOSK"));
        assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize("IPA-Phonemes"));
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN, ClientVoiceConfig.normalize("ZH-CN"));
        assertEquals(ClientVoiceConfig.ENGINE_SHERPA_SENSEVOICE, ClientVoiceConfig.normalize("Korean"));
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
                "sherpa-zh-en", "sherpa-sensevoice",
                "vosk-en", "vosk-cn", "vosk-jp", "vosk-kr",
                "vosk-en-us", "vosk-zh-cn", "vosk-ja-jp", "vosk-ko-kr"}) {
            String norm = ClientVoiceConfig.normalize(alias);
            assertTrue(ClientVoiceConfig.isValidEngine(norm), alias + " -> " + norm);
        }
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_SHERPA_ZH_EN));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_SHERPA_SENSEVOICE));
        assertTrue(ClientVoiceConfig.isValidEngine(ClientVoiceConfig.ENGINE_IPA));
        assertFalse(ClientVoiceConfig.isValidEngine("vosk-ru-ru"));
        assertFalse(ClientVoiceConfig.isValidEngine("vosk-cnn"));
    }
}
