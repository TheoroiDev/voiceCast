package com.theo.voicecast.net;

import com.theo.voicecast.VoiceCast;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Audio / recognizer network channel. The client sends compressed mic audio
 * (Opus) and session control; the server performs all recognition and sends
 * back recognizer state and transcripts.
 *
 * <ul>
     *   <li>C2S {@code audio/frame}: byte payloadType (1=Opus), byte[] data</li>
     *   <li>C2S {@code audio/ctrl}:  byte action (1=BEGIN, 2=FLUSH, 3=END)</li>
     *   <li>S2C {@code state}:      ordinal + lang key + args</li>
     *   <li>S2C {@code transcript}: partial flag, text, confidence, startMs</li>
 * </ul>
 */
public final class VoiceCastNetwork {
    public static final ResourceLocation CHANNEL_FRAME = new ResourceLocation(VoiceCast.MOD_ID, "audio/frame");
    public static final ResourceLocation CHANNEL_CTRL = new ResourceLocation(VoiceCast.MOD_ID, "audio/ctrl");
    public static final ResourceLocation CHANNEL_SELECT = new ResourceLocation(VoiceCast.MOD_ID, "audio/select");
    public static final ResourceLocation CHANNEL_STATE = new ResourceLocation(VoiceCast.MOD_ID, "state");
    public static final ResourceLocation CHANNEL_TRANSCRIPT = new ResourceLocation(VoiceCast.MOD_ID, "transcript");

    public static final byte PAYLOAD_OPUS = 1;
    public static final byte ACT_BEGIN = 1;
    public static final byte ACT_FLUSH = 2;
    public static final byte ACT_END = 3;

    public static final int MAX_PAYLOAD = 4096;

    private static boolean initialized;

    private VoiceCastNetwork() {}

    /** Register C2S receivers (safe on both sides; no client classes). Idempotent. */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHANNEL_FRAME, (buf, ctx) -> {
            byte type = buf.readByte();
            int len = Math.min(buf.readableBytes(), MAX_PAYLOAD);
            byte[] data = new byte[len];
            buf.readBytes(data);
            ctx.queue(() -> com.theo.voicecast.server.VoiceCastServer.INSTANCE
                    .onAudioFrame(ctx.getPlayer(), type, data));
        });
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHANNEL_CTRL, (buf, ctx) -> {
            byte action = buf.readByte();
            ctx.queue(() -> com.theo.voicecast.server.VoiceCastServer.INSTANCE
                    .onControl(ctx.getPlayer(), action));
        });
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHANNEL_SELECT, (buf, ctx) -> {
            String engine = buf.readUtf(32);
            ctx.queue(() -> com.theo.voicecast.server.VoiceCastServer.INSTANCE
                    .onSelect(ctx.getPlayer(), engine));
        });
    }

    // ---- client -> server send helpers (client only) --------------------

    public static void sendFrame(byte payloadType, byte[] data) {
        if (data.length > MAX_PAYLOAD) {
            VoiceCast.LOGGER.warn("Audio frame too large ({} B), dropping", data.length);
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(payloadType);
        buf.writeBytes(data);
        NetworkManager.sendToServer(CHANNEL_FRAME, buf);
    }

    public static void sendControl(byte action) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(action);
        NetworkManager.sendToServer(CHANNEL_CTRL, buf);
    }

    /** Client -> server: request this recognizer engine for the speaker. */
    public static void sendSelect(String engineId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(engineId == null ? "" : engineId, 32);
        NetworkManager.sendToServer(CHANNEL_SELECT, buf);
    }

    // ---- server -> client encode helpers -------------------------------

    /**
     * State messages are translation keys + args (localized on the client), not
     * pre-formatted English text.
     */
    public static FriendlyByteBuf encodeState(int ordinal, String key, java.util.List<String> args) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(ordinal);
        buf.writeUtf(key == null ? "" : key, 256);
        int n = args == null ? 0 : Math.min(args.size(), 8);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUtf(args.get(i) == null ? "" : args.get(i), 256);
        return buf;
    }

    public static FriendlyByteBuf encodeTranscript(boolean partial, String text, float confidence, long startMs) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(partial);
        buf.writeUtf(text == null ? "" : text, 1024);
        buf.writeFloat(confidence);
        buf.writeLong(startMs);
        return buf;
    }
}
