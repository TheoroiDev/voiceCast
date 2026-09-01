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
    private static boolean initialized;

    private ClientNet() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        EnginePicker.registerCommands();

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
            float confidence = buf.readFloat();
            long startMs = buf.readLong();
            ctx.queue(() -> {
                RecognitionResult r = partial
                        ? RecognitionResult.partial(text, java.util.List.of(), confidence)
                        : RecognitionResult.finality(text, java.util.List.of(), confidence, startMs);
                VoiceCastEvents.post(partial ? new RecognitionPartialEvent(r) : new RecognitionFinalEvent(r));
            });
        });
    }

    /** True when the player is connected to a server (audio can be streamed). */
    public static boolean connected() {
        return Minecraft.getInstance().getConnection() != null;
    }
}
