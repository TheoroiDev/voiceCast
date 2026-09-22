package com.theo.voicecast.client;

import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognitionFinalEvent;
import com.theo.voicecast.api.event.RecognitionPartialEvent;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.api.event.RecognizerStateEvent;
import com.theo.voicecast.net.VoiceCastNetwork;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;

/**
 * Client-side S2C receivers: the server pushes recognizer state and transcripts;
 * here they are marshaled onto the client thread and re-posted to the local
 * {@link VoiceCastEvents} bus so the HUD and consumers are engine-agnostic.
 */
public final class ClientNet {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("VoiceCast");
    private static boolean initialized;

    private ClientNet() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        // On (re)joining a world, tell the server which engine the player wants.
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> EnginePicker.onJoin());

        NetworkManager.registerReceiver(NetworkManager.s2c(), VoiceCastNetwork.CHANNEL_STATE, (buf, ctx) -> {
            int ordinal = buf.readInt();
            String key = buf.readUtf(256);
            int argCount = Math.min(buf.readVarInt(), 8);
            java.util.List<String> args = new java.util.ArrayList<>(argCount);
            for (int i = 0; i < argCount; i++) args.add(buf.readUtf(256));
            RecognizerState state;
            try {
                state = RecognizerState.values()[ordinal];
            } catch (ArrayIndexOutOfBoundsException e) {
                state = RecognizerState.READY;
            }
            final RecognizerState fs = state;
            ctx.queue(() -> VoiceCastEvents.post(new RecognizerStateEvent(fs, key, args)));
        });

        NetworkManager.registerReceiver(NetworkManager.s2c(), VoiceCastNetwork.CHANNEL_TRANSCRIPT, (buf, ctx) -> {
            boolean partial = buf.readBoolean();
            String text = buf.readUtf(1024);
            float score = buf.readFloat();
            long startMs = buf.readLong();
            int decisionOrdinal = buf.readVarInt();
            String spellId = buf.readUtf(256);
            ctx.queue(() -> {
                // Semantic contract v2: the server-side session adjudicated;
                // the wire carries the decision so client-side consumers see
                // the same verdict. Partial results carry no decision.
                RecognitionResult r;
                if (partial) {
                    r = RecognitionResult.partial(text, "");
                } else {
                    // R2 F-B5: guard the ordinal like the state decode above —
                    // a skewing server must degrade to "undecided", not crash
                    // the netty thread with an AIOOBE.
                    com.theo.voicecast.api.Decision decision = null;
                    com.theo.voicecast.api.Decision[] values = com.theo.voicecast.api.Decision.values();
                    if (decisionOrdinal >= 0 && decisionOrdinal < values.length) {
                        decision = values[decisionOrdinal];
                    } else {
                        LOGGER.warn("Unknown decision ordinal {} from server (version skew?); treating as undecided",
                                decisionOrdinal);
                    }
                    // R2 F-B8: the wire carries no pronunciation id — passing
                    // spellId here impersonated it; unknown ("") is honest.
                    r = new RecognitionResult(text, "", "", decision, spellId, "",
                            score, java.util.List.of(), startMs, startMs);
                }
                VoiceCastEvents.post(partial ? new RecognitionPartialEvent(r) : new RecognitionFinalEvent(r));
            });
        });
    }

    /** True when the player is connected to a server (audio can be streamed). */
    public static boolean connected() {
        return Minecraft.getInstance().getConnection() != null;
    }
}
