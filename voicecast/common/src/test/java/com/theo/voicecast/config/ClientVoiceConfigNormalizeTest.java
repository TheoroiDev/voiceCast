package com.theo.voicecast.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientVoiceConfigNormalizeTest {

    @Test
    void voskAliasesNormalizeToCanonicalId() {
        for (String alias : new String[]{"vosk", "text", "vosk-text", "word",
                "vosk-en-us", "en-us", "en", "english"}) {
            assertEquals(ClientVoiceConfig.ENGINE_VOSK, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void ipaAliasesNormalizeToCanonicalId() {
        for (String alias : new String[]{"ipa", "phoneme", "phonemes", "ipa-phonemes"}) {
            assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize(alias), alias);
        }
    }

    @Test
    void caseInsensitive() {
        assertEquals(ClientVoiceConfig.ENGINE_VOSK, ClientVoiceConfig.normalize("VOSK"));
        assertEquals(ClientVoiceConfig.ENGINE_IPA, ClientVoiceConfig.normalize("IPA-Phonemes"));
    }

    @Test
    void unknownIsNull() {
        assertNull(ClientVoiceConfig.normalize("whisper"));
        assertNull(ClientVoiceConfig.normalize(""));
        assertNull(ClientVoiceConfig.normalize(null));
    }

    @Test
    void normalizedValuesAreValidEngines() {
        for (String alias : new String[]{"vosk", "ipa", "en", "english", "word"}) {
            String norm = ClientVoiceConfig.normalize(alias);
            assertTrue(ClientVoiceConfig.isValidEngine(norm), alias + " -> " + norm);
        }
    }
}
