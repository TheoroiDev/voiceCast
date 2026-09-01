package com.theo.voicecast.client;

import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.audio.OpusAudioCodec;
import com.theo.voicecast.net.VoiceCastNetwork;
import java.util.Collection;
import net.minecraft.client.Minecraft;

/**
 * Client-side "recognizer" that performs no recognition locally: it Opus-encodes
 * the mic audio and streams it to the server, which runs Vosk/IPA and sends back
 * transcripts. The client downloads no speech model and does no inference.
 */
public final class RemoteSpeechSession implements SpeechRecognizer {
    private final OpusAudioCodec codec = new OpusAudioCodec();
    private final short[] accum = new short[OpusAudioCodec.FRAME_SAMPLES * 4];
    private int accumLen;
    private boolean active;

    @Override public String id() { return "remote"; }
    @Override public String displayName() { return "Remote (server-side recognition)"; }
    @Override public boolean isActive() { return active; }

    private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getConnection() != null;
    }

    @Override
    public synchronized void start(SpeechOptions options) {
        accumLen = 0;
        active = true;
        if (inWorld()) VoiceCastNetwork.sendControl(VoiceCastNetwork.ACT_BEGIN);
    }

    @Override
    public synchronized void stop() {
        if (active && inWorld()) VoiceCastNetwork.sendControl(VoiceCastNetwork.ACT_END);
        active = false;
        accumLen = 0;
    }

    @Override
    public void setVocabulary(Collection<Pronunciation> vocabulary) {
        // Vocabulary is managed server-side; nothing to do here.
    }

    @Override
    public synchronized void acceptPcm(short[] samples, int offset, int length) {
        if (!active || !inWorld()) return;
        int n = Math.min(length, accum.length - accumLen);
        System.arraycopy(samples, offset, accum, accumLen, n);
        accumLen += n;
        while (accumLen >= OpusAudioCodec.FRAME_SAMPLES) {
            short[] frame = new short[OpusAudioCodec.FRAME_SAMPLES];
            System.arraycopy(accum, 0, frame, 0, OpusAudioCodec.FRAME_SAMPLES);
            int leftover = accumLen - OpusAudioCodec.FRAME_SAMPLES;
            System.arraycopy(accum, OpusAudioCodec.FRAME_SAMPLES, accum, 0, leftover);
            accumLen = leftover;
            byte[] data = codec.encode(frame);
            VoiceCastNetwork.sendFrame(VoiceCastNetwork.PAYLOAD_OPUS, data);
        }
    }

    @Override
    public synchronized void finishUtterance() {
        if (!active || !inWorld()) { accumLen = 0; return; }
        // Flush any remaining buffered audio as a padded (silence) frame.
        if (accumLen > 0) {
            short[] frame = new short[OpusAudioCodec.FRAME_SAMPLES];
            System.arraycopy(accum, 0, frame, 0, accumLen);
            accumLen = 0;
            VoiceCastNetwork.sendFrame(VoiceCastNetwork.PAYLOAD_OPUS, codec.encode(frame));
        }
        VoiceCastNetwork.sendControl(VoiceCastNetwork.ACT_FLUSH);
    }
}
