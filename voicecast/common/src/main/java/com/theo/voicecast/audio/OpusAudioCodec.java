package com.theo.voicecast.audio;

import com.theo.voicecast.VoiceCast;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Opus codec wrapper for the client-&gt;server audio channel.
 *
 * <p>Input is 16 kHz / 16-bit / mono PCM. A 200 ms frame ({@link #FRAME_SAMPLES}
 * short samples) is split into ten 20 ms subframes ({@link #SUBFRAME_SAMPLES})
 * and each is encoded independently in VoIP voice mode (~24 kbps), then
 * concatenated as {@code [ushort len][bytes]...} so the decoder needs no
 * cross-frame state.
 */
public final class OpusAudioCodec {
    public static final int SAMPLE_RATE = 16_000;
    public static final int CHANNELS = 1;
    public static final int SUBFRAME_SAMPLES = SAMPLE_RATE / 50;   // 320 (20 ms)
    public static final int SUBFRAMES_PER_FRAME = 10;
    public static final int FRAME_SAMPLES = SUBFRAME_SAMPLES * SUBFRAMES_PER_FRAME; // 3200 (200 ms)
    private static final int BITRATE = 24_000;
    private static final int MAX_PACKET = 4000;

    private final OpusEncoder encoder;
    private final OpusDecoder decoder;
    private final byte[] packetBuf = new byte[MAX_PACKET];

    public OpusAudioCodec() {
        OpusEncoder enc = null;
        OpusDecoder dec = null;
        try {
            enc = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
            enc.setBitrate(BITRATE);
            try { enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE); } catch (Throwable ignored) {}
            dec = new OpusDecoder(SAMPLE_RATE, CHANNELS);
        } catch (Throwable t) {
            VoiceCast.LOGGER.error("Failed to initialize Opus codec", t);
        }
        this.encoder = enc;
        this.decoder = dec;
    }

    /** Encode exactly {@link #FRAME_SAMPLES} samples (pads/truncates if needed). */
    public byte[] encode(short[] pcm) {
        short[] frame = pcm;
        if (frame.length != FRAME_SAMPLES) {
            frame = new short[FRAME_SAMPLES];
            System.arraycopy(pcm, 0, frame, 0, Math.min(pcm.length, FRAME_SAMPLES));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(800);
        byte[] hdr = new byte[2];
        for (int s = 0; s < SUBFRAMES_PER_FRAME; s++) {
            int n;
            try {
                n = encoder.encode(frame, s * SUBFRAME_SAMPLES, SUBFRAME_SAMPLES, packetBuf, 0, packetBuf.length);
            } catch (Throwable t) {
                VoiceCast.LOGGER.warn("Opus encode failed at subframe {}", s, t);
                n = 0;
            }
            if (n < 0) n = 0;
            hdr[0] = (byte) (n & 0xFF);
            hdr[1] = (byte) ((n >> 8) & 0xFF);
            out.writeBytes(hdr);
            if (n > 0) out.write(packetBuf, 0, n);
        }
        return out.toByteArray();
    }

    /** Decode a frame produced by {@link #encode} back to PCM samples. */
    public short[] decode(byte[] data) {
        List<short[]> subframes = new ArrayList<>(SUBFRAMES_PER_FRAME);
        int total = 0;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.remaining() >= 2 && subframes.size() < SUBFRAMES_PER_FRAME) {
            int len = buf.getShort() & 0xFFFF;
            if (len < 0 || len > buf.remaining()) {
                VoiceCast.LOGGER.warn("Opus frame truncated (len={}, remaining={})", len, buf.remaining());
                break;
            }
            byte[] packet = new byte[len];
            if (len > 0) buf.get(packet);
            short[] pcm = new short[SUBFRAME_SAMPLES];
            try {
                int decoded = decoder.decode(packet, 0, packet.length, pcm, 0, SUBFRAME_SAMPLES, false);
                if (decoded < SUBFRAME_SAMPLES) {
                    // pad missing tail with silence
                    for (int i = Math.max(0, decoded); i < SUBFRAME_SAMPLES; i++) pcm[i] = 0;
                }
            } catch (Throwable t) {
                VoiceCast.LOGGER.warn("Opus decode failed", t);
                java.util.Arrays.fill(pcm, (short) 0);
            }
            subframes.add(pcm);
            total += pcm.length;
        }
        short[] out = new short[total];
        int off = 0;
        for (short[] sf : subframes) {
            System.arraycopy(sf, 0, out, off, sf.length);
            off += sf.length;
        }
        return out;
    }
}
