package com.theo.voicecast;

import com.theo.voicecast.api.RecognizerRegistry;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognitionFinalEvent;
import com.theo.voicecast.engine.IpaPhonemeRecognizer;
import com.theo.voicecast.engine.NoopRecognizer;
import com.theo.voicecast.engine.VoskTextRecognizer;
import com.theo.voicecast.net.VoiceCastNetwork;
import com.theo.voicecast.server.VoiceCastServer;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * VoiceCast - a loader-agnostic offline speech recognition library for Minecraft mods.
 *
 * <p>Loader modules call {@link #init()} during common initialization to wire up
 * built-in recognizer backends and log the active version.
 */
public final class VoiceCast {
    public static final String MOD_ID = "voicecast";
    public static final Logger LOGGER = LoggerFactory.getLogger("VoiceCast");

    private static boolean initialized;

    private VoiceCast() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("VoiceCast common initializing");

        RecognizerRegistry.register("noop", NoopRecognizer::new);
        RecognizerRegistry.register("vosk-text", VoskTextRecognizer::new);
        // en-us alias (canonical: vosk-text). Language-tagged ids (vosk-zh-cn, ...)
        // will follow the same pattern when more Vosk models are wired up.
        RecognizerRegistry.register("vosk-en-us", VoskTextRecognizer::new);
        RecognizerRegistry.register("ipa-phonemes", IpaPhonemeRecognizer::new);
        RecognizerRegistry.setDefault("vosk-text");

        VoiceCastEvents.subscribe(RecognitionFinalEvent.class, e -> {
            String text = e.result() == null ? "" : e.result().text();
            if (!text.isBlank()) {
                LOGGER.info("[VoiceCast] heard (client): '{}' (conf={})",
                        text.trim().toLowerCase(Locale.ROOT), e.result().confidence());
            }
        });

        // Server lifecycle: run recognition server-side.
        LifecycleEvent.SERVER_STARTING.register(server -> VoiceCastServer.INSTANCE.start(server));
        LifecycleEvent.SERVER_STOPPED.register(server -> VoiceCastServer.INSTANCE.stop());
        PlayerEvent.PLAYER_QUIT.register(player -> VoiceCastServer.INSTANCE.onPlayerQuit(player));

        LOGGER.info("VoiceCast available recognizers: {}", RecognizerRegistry.ids());
    }
}
