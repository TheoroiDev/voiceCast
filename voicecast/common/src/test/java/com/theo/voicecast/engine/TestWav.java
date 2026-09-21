package com.theo.voicecast.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal 16 kHz mono 16-bit PCM WAV reader for engine tests. */
final class TestWav {
    private TestWav() {}

    /** Read a PCM16 WAV into normalized [-1, 1) floats at its native rate. */
    static float[] readMono16k(Path wav) throws IOException {
        byte[] all = Files.readAllBytes(wav);
        ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.get() != 'R' || buf.get() != 'I' || buf.get() != 'F' || buf.get() != 'F') {
            throw new IOException("not a RIFF file: " + wav);
        }
        buf.getInt(); // riff size
        for (int i = 0; i < 4; i++) buf.get(); // WAVE
        int channels = 0;
        int rate = 0;
        int bits = 0;
        float[] samples = null;
        while (buf.remaining() >= 8) {
            int id = buf.getInt();
            int size = buf.getInt();
            if (id == chunkId("fmt ")) {
                buf.getShort(); // audio format
                channels = buf.getShort() & 0xFFFF;
                rate = buf.getInt();
                buf.getInt(); // byte rate
                buf.getShort(); // block align
                bits = buf.getShort() & 0xFFFF;
                size -= 16;
            } else if (id == chunkId("data")) {
                int n = size / 2;
                samples = new float[n];
                for (int i = 0; i < n; i++) {
                    samples[i] = buf.getShort() / 32768.0f;
                }
                if ((size & 1) != 0) buf.get(); // padding byte
                size = 0;
            }
            // skip any remaining chunk payload
            int skip = Math.max(0, size);
            if (skip > buf.remaining()) skip = buf.remaining();
            buf.position(buf.position() + skip);
        }
        if (samples == null) throw new IOException("no data chunk in " + wav);
        if (rate != 16000 || channels != 1 || bits != 16) {
            throw new IOException("expected 16 kHz mono PCM16, got " + rate + " Hz " + channels + "ch " + bits + " bit");
        }
        return samples;
    }

    private static int chunkId(String s) {
        return (s.charAt(0)) | (s.charAt(1) << 8) | (s.charAt(2) << 16) | (s.charAt(3) << 24);
    }
}
