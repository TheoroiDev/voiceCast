package com.theo.voicecast.audio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpusAudioCodecTest {

    /** 220 Hz + 440 Hz mix, speech-ish amplitude. */
    private static short[] sine(int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            double t = i / (double) OpusAudioCodec.SAMPLE_RATE;
            pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * 220 * t)
                    + 4000 * Math.sin(2 * Math.PI * 440 * t));
        }
        return pcm;
    }

    private static double rms(short[] a) {
        double s = 0;
        for (short v : a) s += (double) v * v;
        return Math.sqrt(s / a.length);
    }

    private static int zeroCrossings(short[] a) {
        int zc = 0;
        for (int i = 1; i < a.length; i++) {
            if ((a[i - 1] < 0) != (a[i] < 0)) zc++;
        }
        return zc;
    }

    /** Sum of the 2-byte little-endian length headers in an encoded frame. */
    private static int payloadBytes(byte[] packet) {
        int payload = 0;
        int off = 0;
        while (off + 2 <= packet.length) {
            int len = (packet[off] & 0xFF) | ((packet[off + 1] & 0xFF) << 8);
            payload += len;
            off += 2 + len;
        }
        return payload;
    }

    /**
     * Opus is a transform (CELT/MDCT) coder: it preserves the perceptual
     * content (envelope, spectral shape) but NOT sample-exact phase, so a
     * waveform-level SNR is meaningless for a pure tone. Assert the properties
     * the recognition pipeline actually depends on: level and dominant
     * frequency survive the roundtrip.
     */
    @Test
    void encodeDecodeRoundtripPreservesLevelAndFrequency() {
        OpusAudioCodec codec = new OpusAudioCodec();
        short[] pcm = sine(OpusAudioCodec.FRAME_SAMPLES);
        byte[] packet = codec.encode(pcm);
        // 10 subframes, each with a 2-byte little-endian length header.
        assertEquals(OpusAudioCodec.SUBFRAMES_PER_FRAME * 2, packet.length - payloadBytes(packet));
        assertTrue(packet.length > OpusAudioCodec.SUBFRAMES_PER_FRAME * 2, "packet must contain payloads");

        short[] decoded = codec.decode(packet);
        assertEquals(OpusAudioCodec.FRAME_SAMPLES, decoded.length);

        double rmsIn = rms(pcm);
        double rmsOut = rms(decoded);
        double rmsDbDiff = Math.abs(20.0 * Math.log10(rmsOut / rmsIn));
        assertTrue(rmsDbDiff < 2.0, "RMS level drifted by " + rmsDbDiff + " dB");

        double zcRatio = (double) zeroCrossings(decoded) / zeroCrossings(pcm);
        assertTrue(zcRatio > 0.8 && zcRatio < 1.25, "dominant frequency changed, zc ratio " + zcRatio);
    }

    @Test
    void packetSizeStaysWellBelowNetworkCap() {
        OpusAudioCodec codec = new OpusAudioCodec();
        byte[] packet = codec.encode(sine(OpusAudioCodec.FRAME_SAMPLES));
        assertTrue(packet.length < 4000, "200ms frame at 24kbps must stay far below 4KB: " + packet.length);
    }

    @Test
    void shortInputIsPaddedToFullFrame() {
        OpusAudioCodec codec = new OpusAudioCodec();
        short[] decoded = codec.decode(codec.encode(sine(100)));
        assertEquals(OpusAudioCodec.FRAME_SAMPLES, decoded.length);
    }

    @Test
    void truncatedPacketDoesNotThrow() {
        OpusAudioCodec codec = new OpusAudioCodec();
        byte[] packet = codec.encode(sine(OpusAudioCodec.FRAME_SAMPLES));
        short[] decoded = codec.decode(Arrays.copyOf(packet, 5));
        assertTrue(decoded.length <= OpusAudioCodec.FRAME_SAMPLES);
    }

    @Test
    void garbageBytesDoNotThrow() {
        OpusAudioCodec codec = new OpusAudioCodec();
        short[] decoded = codec.decode(new byte[]{0x7f, 0x7f, 1, 2, 3, 4, 5});
        assertTrue(decoded.length <= OpusAudioCodec.FRAME_SAMPLES);
    }

    @Test
    void encodeAcceptsArbitraryLengths() {
        OpusAudioCodec codec = new OpusAudioCodec();
        // Payload size legitimately depends on content (silence encodes tiny),
        // but every frame decodes back to exactly FRAME_SAMPLES.
        short[] d1 = codec.decode(codec.encode(sine(1)));
        short[] d2 = codec.decode(codec.encode(sine(OpusAudioCodec.FRAME_SAMPLES * 3)));
        assertEquals(OpusAudioCodec.FRAME_SAMPLES, d1.length);
        assertEquals(OpusAudioCodec.FRAME_SAMPLES, d2.length);
    }
}
