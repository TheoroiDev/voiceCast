package com.theo.voicecast;

import com.theo.voicecast.api.RecognizerRegistry;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognitionFinalEvent;
import com.theo.voicecast.api.engine.EngineFamilies;
import com.theo.voicecast.engine.NoopRecognizer;
import com.theo.voicecast.engine.SherpaQwen3Recognizer;
import com.theo.voicecast.engine.ZipaPhonemeRecognizer;
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
        // The builtin engine ids are catalog model names (models.json, one
        // model = one engine). The recognizers below are the builtin family
        // backends; the model path/word list arrive via EngineSpec/SpeechOptions
        // at session build time (VoiceCastServer.createRecognizer/configure).
        RecognizerRegistry.register("zipa-ipa", ZipaPhonemeRecognizer::new);
        RecognizerRegistry.register("qwen3-asr-0.6b-int8", SherpaQwen3Recognizer::new);
        RecognizerRegistry.setDefault("qwen3-asr-0.6b-int8");

        // Builtin engine families (voiceCast#42): addon mods register theirs in
        // their own init via EngineFamilies.register(type, factory).
        EngineFamilies.register("ipa", spec -> new com.theo.voicecast.engine.ZipaPhonemeRecognizer());
        EngineFamilies.register("sherpa-qwen3", com.theo.voicecast.engine.SherpaQwen3Recognizer::new);

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
