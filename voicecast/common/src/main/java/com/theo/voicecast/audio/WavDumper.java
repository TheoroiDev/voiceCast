package com.theo.voicecast.audio;

import com.theo.voicecast.VoiceCast;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streams 16-bit PCM samples into a WAV file for offline debugging.
 * Caller is responsible for {@link #close()}, which writes the RIFF header.
 */
public final class WavDumper implements AutoCloseable {
    private final Path path;
    private final AudioFormat format;
    private final OutputStream out;
    private long dataBytes;

    public WavDumper(Path path, AudioFormat format) throws IOException {
        this.path = path;
        this.format = format;
        Files.createDirectories(path.getParent());
        this.out = Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        byte[] header = new byte[44];
        out.write(header);
    }

    public synchronized void write(short[] samples, int offset, int length) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) bb.putShort(samples[offset + i]);
        out.write(bb.array());
        dataBytes += (long) length * 2;
    }

    @Override
    public synchronized void close() {
        try {
            out.flush();
            long total = dataBytes + 36;
            byte[] header = new byte[44];
            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes());
            bb.putInt((int) total);
            bb.put("WAVE".getBytes());
            bb.put("fmt ".getBytes());
            bb.putInt(16);
            bb.putShort((short) 1);
            bb.putShort((short) format.getChannels());
            bb.putInt((int) format.getSampleRate());
            bb.putInt((int) (format.getSampleRate() * format.getFrameSize()));
            bb.putShort((short) format.getFrameSize());
            bb.putShort((short) format.getSampleSizeInBits());
            bb.put("data".getBytes());
            bb.putInt((int) dataBytes);
            out.close();
        } catch (IOException e) {
            VoiceCast.LOGGER.warn("Failed to finalize WAV {}", path, e);
        }
    }
}
