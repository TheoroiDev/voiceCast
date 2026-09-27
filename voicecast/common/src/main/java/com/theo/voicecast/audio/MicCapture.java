package com.theo.voicecast.audio;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.AudioLevelEvent;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Push-to-talk microphone capture. Opens the default {@link TargetDataLine}
 * while actively listening, releases it on stop to avoid conflicting with
 * other voice mods (Simple Voice Chat, Plasmo Voice, etc.).
 *
 * <p>Audio format: 16 kHz, 16-bit, mono, signed PCM.
 */
public final class MicCapture {
    public static final float SAMPLE_RATE = 16_000f;
    public static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private final AudioFormat format;
    private final int frameMs;
    private final Listener listener;
    /** Optional recognition-path noise suppression (null = off). */
    private final NoiseSuppression noiseSuppression;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile TargetDataLine line;
    private Thread thread;

    public interface Listener {
        void onPcm(short[] samples, int offset, int length);
    }

    public MicCapture(Listener listener) {
        this(FORMAT, 200, listener, null);
    }

    public MicCapture(Listener listener, NoiseSuppression noiseSuppression) {
        this(FORMAT, 200, listener, noiseSuppression);
    }

    public MicCapture(AudioFormat format, int frameMs, Listener listener) {
        this(format, frameMs, listener, null);
    }

    public MicCapture(AudioFormat format, int frameMs, Listener listener, NoiseSuppression noiseSuppression) {
        this.format = format;
        this.frameMs = frameMs;
        this.listener = listener;
        this.noiseSuppression = noiseSuppression;
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized void start() {
        if (running.get()) return;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                VoiceCast.LOGGER.warn("Microphone line not supported for format {}", format);
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            running.set(true);
            thread = new Thread(this::loop, "VoiceCast-Mic");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            VoiceCast.LOGGER.error("Failed to open microphone", t);
            running.set(false);
            closeQuietly();
        }
    }

    public synchronized void stop() {
        running.set(false);
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000); // refine R2: never release() mid native process()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly();
        if (noiseSuppression != null) {
            noiseSuppression.release();
        }
    }

    private void loop() {
        int frameSize = (int) (format.getSampleRate() * (frameMs / 1000.0));
        byte[] buf = new byte[frameSize * format.getFrameSize()];
        long chunks = 0;
        long startedNanos = System.nanoTime();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int read;
            try {
                read = line.read(buf, 0, buf.length);
            } catch (Throwable t) {
                VoiceCast.LOGGER.warn("Mic read failed", t);
                break;
            }
            if (read <= 0) continue;
            int samples = read / 2;
            short[] pcm = new short[samples];
            for (int i = 0; i < samples; i++) {
                int lo = buf[i * 2] & 0xff;
                int hi = buf[i * 2 + 1];
                pcm[i] = (short) ((hi << 8) | lo);
            }
            double sum = 0;
            for (short s : pcm) sum += (double) s * s;
            float rms = (float) Math.sqrt(sum / Math.max(1, samples)) / 32768f;
            VoiceCastEvents.post(new AudioLevelEvent(rms));
                if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
                    chunks++;
                    long n = com.theo.voicecast.config.VoiceCastConfig.INSTANCE.logEveryNChunks;
                    if (chunks % Math.max(1, n) == 0) {
                        double elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000.0;
                        VoiceCast.LOGGER.info("[Mic] chunk #{} samples={} rms={} elapsedMs={}",
                                chunks, samples, String.format(java.util.Locale.ROOT, "%.4f", rms), Math.round(elapsedMs));
                    }
                }
            // Recognition-path DSP goes here: after the level meter (raw
            // signal), before the recognizer consumer. stop() releases the
            // denoiser, so no filter state leaks between PTT sessions.
            if (noiseSuppression != null) {
                noiseSuppression.process(pcm, 0, samples);
            }
            try {
                listener.onPcm(pcm, 0, samples);
            } catch (Throwable t) {
                VoiceCast.LOGGER.error("Mic listener threw", t);
            }
        }
        VoiceCast.LOGGER.info("[Mic] capture loop exited after {} chunks", chunks);
    }

    private void closeQuietly() {
        if (line != null) {
            try { line.stop(); } catch (Throwable ignored) {}
            try { line.close(); } catch (Throwable ignored) {}
            line = null;
        }
    }
}
