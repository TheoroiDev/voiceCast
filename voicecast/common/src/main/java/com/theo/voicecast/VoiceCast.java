package com.theo.voicecast;

import com.theo.voicecast.api.RecognizerRegistry;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognitionFinalEvent;
import com.theo.voicecast.api.engine.EngineFamilies;
import com.theo.voicecast.engine.IpaPhonemeRecognizer;
import com.theo.voicecast.engine.NoopRecognizer;
import com.theo.voicecast.engine.SherpaSenseVoiceRecognizer;
import com.theo.voicecast.engine.SherpaStreamingRecognizer;
import com.theo.voicecast.server.VoiceCastServerCommands;
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
        // All Vosk word engines share VoskTextRecognizer; the language comes
        // from the engine id's two-letter code (vosk-en/zh/ja/ko, unified in
        // 0.4.0; legacy vosk-cn/jp/kr ids migrate via config normalize) and is
        // resolved at configure time from models.json (VoiceCastServer.
        // attachSharedModel/configure + engineLanguage routing).
        RecognizerRegistry.register("sherpa-zh-en", SherpaStreamingRecognizer::new);
        RecognizerRegistry.register("sherpa-sensevoice", SherpaSenseVoiceRecognizer::new);
        RecognizerRegistry.register("ipa-phonemes", IpaPhonemeRecognizer::new);
        RecognizerRegistry.setDefault("sherpa-zh-en");

        // Builtin engine families (voiceCast#42): addon mods register theirs in
        // their own init via EngineFamilies.register(type, factory).
        EngineFamilies.register("ipa", spec -> new com.theo.voicecast.engine.IpaPhonemeRecognizer());
        EngineFamilies.register("sherpa-streaming", com.theo.voicecast.engine.SherpaStreamingRecognizer::new);
        EngineFamilies.register("sherpa-sensevoice", com.theo.voicecast.engine.SherpaSenseVoiceRecognizer::new);

        VoiceCastServerCommands.register(); // /voicecast status|engine|enabled|whitelist|reload (#29)

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
