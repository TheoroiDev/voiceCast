package com.theo.voicecast.client;

import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.AudioLevelEvent;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.api.event.RecognizerStateEvent;
import com.theo.voicecast.audio.MicCapture;
import com.theo.voicecast.audio.WavDumper;
import com.theo.voicecast.client.hud.VoiceCastHud;
import com.theo.voicecast.config.VoiceCastConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Client-side controller. With server-side recognition the client only:
 * captures the mic, runs VAD/endpoint detection, Opus-encodes via
 * {@link RemoteSpeechSession}, and renders HUD. It downloads no model and runs
 * no Vosk/ONNX inference; recognizer state/transcripts arrive from the server.
 */
public enum VoiceCastClient {
    INSTANCE;

    private SpeechRecognizer recognizer;
    private MicCapture mic;
    private WavDumper dumper;

    private boolean enabled;
    private boolean pttHeld;
    private boolean micWanted;
    private boolean started;

    private long speechFirstMs;
    private long speechLastLoudMs;

    public SpeechRecognizer recognizer() { return recognizer; }

    public synchronized void init() {
        if (started) return;
        started = true;
        ClientNet.init();
        reload();
    }

    /** (Re)create the remote session. */
    public synchronized void reload() {
        shutdownRecognizer();
        try {
            recognizer = new RemoteSpeechSession();
            SpeechOptions opts = SpeechOptions.defaults();
            recognizer.start(opts);
            VoiceCastEvents.post(new RecognizerStateEvent(RecognizerState.READY, "voicecast.state.ready", java.util.List.of()));
        } catch (Throwable t) {
            com.theo.voicecast.VoiceCast.LOGGER.error("Failed to start remote speech session", t);
            VoiceCastEvents.post(new RecognizerStateEvent(RecognizerState.ERROR,
                    "voicecast.state.error", java.util.List.of(String.valueOf(t.getMessage()))));
        }
    }

    public synchronized void shutdown() {
        shutdownRecognizer();
        started = false;
    }

    private void shutdownRecognizer() {
        if (mic != null) { mic.stop(); mic = null; }
        if (dumper != null) { dumper.close(); dumper = null; }
        if (recognizer != null) {
            try { recognizer.stop(); } catch (Throwable t) { com.theo.voicecast.VoiceCast.LOGGER.warn("recognizer stop failed", t); }
            recognizer = null;
        }
    }

    public void setVocabulary(Collection<Pronunciation> vocabulary) {
        // Vocabulary is managed server-side; kept for API compatibility.
    }

    /** Enables or disables the whole VoiceCast client system (HUD + mic). */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        VoiceCastHud.INSTANCE.setEnabled(enabled);
        if (!enabled) {
            setPttHeld(false);
        }
        updateMicWanted();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setPttHeld(boolean held) {
        if (this.pttHeld == held) return;
        this.pttHeld = held;
        VoiceCastHud.INSTANCE.setPttHeld(held);
        updateMicWanted();
    }

    public boolean isModelDownloading() { return false; }
    public String modelDownloadStatus() { return ""; }

    private void updateMicWanted() {
        boolean want;
        if (!enabled) {
            want = false;
        } else if (!ClientNet.connected()) {
            want = false; // no audio streaming on the title screen / when not in a world
        } else if (recognizer == null) {
            want = false;
        } else {
            // WizardReal controls PTT externally via right-click while holding a staff.
            want = pttHeld;
        }
        if (want == micWanted) return;
        micWanted = want;
        if (want) startMic();
        else stopMic();
    }

    private void startMic() {
        if (mic != null) return;
        speechFirstMs = 0;
        speechLastLoudMs = 0;
        try {
            if (VoiceCastConfig.INSTANCE.saveDebugWav) {
                Path p = Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("config/voicecast/debug-" + System.currentTimeMillis() + ".wav");
                dumper = new WavDumper(p, MicCapture.FORMAT);
            }
        } catch (Exception e) {
            com.theo.voicecast.VoiceCast.LOGGER.warn("Could not open WAV dumper", e);
            dumper = null;
        }
        if (recognizer != null) {
            try { recognizer.start(SpeechOptions.defaults()); }
            catch (Throwable t) { com.theo.voicecast.VoiceCast.LOGGER.warn("remote start failed", t); }
        }
        mic = new MicCapture(this::onPcm);
        mic.start();
        if (!mic.isRunning()) {
            VoiceCastEvents.post(new RecognizerStateEvent(RecognizerState.MICROPHONE_UNAVAILABLE,
                    "voicecast.state.mic_unavailable", java.util.List.of()));
            mic = null;
            if (dumper != null) { dumper.close(); dumper = null; }
        } else {
            VoiceCastEvents.post(new RecognizerStateEvent(RecognizerState.LISTENING,
                    "voicecast.state.listening", java.util.List.of()));
        }
    }

    private void stopMic() {
        flushUtterance();
        if (recognizer != null) {
            try { recognizer.stop(); }
            catch (Throwable t) { com.theo.voicecast.VoiceCast.LOGGER.warn("remote stop failed", t); }
        }
        if (mic != null) { mic.stop(); mic = null; }
        if (dumper != null) { dumper.close(); dumper = null; }
        speechFirstMs = 0;
        speechLastLoudMs = 0;
    }

    private void flushUtterance() {
        SpeechRecognizer r = recognizer;
        if (r != null) {
            try { r.finishUtterance(); }
            catch (Throwable t) { com.theo.voicecast.VoiceCast.LOGGER.warn("finishUtterance failed", t); }
        }
        speechFirstMs = 0;
        speechLastLoudMs = 0;
    }

    private void onPcm(short[] samples, int off, int len) {
        double sum = 0;
        for (int i = off; i < off + len; i++) sum += (double) samples[i] * samples[i];
        float rms = (float) Math.sqrt(sum / Math.max(1, len)) / 32768f;
        VoiceCastEvents.post(new AudioLevelEvent(rms));

        long now = System.currentTimeMillis();
        boolean loud = rms >= VoiceCastConfig.INSTANCE.silenceEndpointRms;
        if (loud) {
            if (speechFirstMs == 0) speechFirstMs = now;
            speechLastLoudMs = now;
        }

        // Energy VAD is not used when an external mod (WizardReal) drives PTT.

        // Silence endpoint (PTT): flush on a long-enough pause so incantations
        // can be chained while holding the key.
        if (VoiceCastConfig.INSTANCE.silenceEndpoint
                && speechFirstMs != 0 && speechLastLoudMs != 0) {
            boolean spokeLongEnough = now - speechFirstMs >= VoiceCastConfig.INSTANCE.minUtteranceMs;
            boolean pausedLongEnough = now - speechLastLoudMs >= VoiceCastConfig.INSTANCE.silenceEndpointMs;
            if (spokeLongEnough && pausedLongEnough && recognizer != null) {
                flushUtterance();
            }
        }

        if (dumper != null) {
            try { dumper.write(samples, off, len); } catch (Exception ignored) {}
        }

        SpeechRecognizer r = recognizer;
        if (r != null) {
            try { r.acceptPcm(samples, off, len); }
            catch (Throwable t) { com.theo.voicecast.VoiceCast.LOGGER.error("remote accept failed", t); }
        }
    }

    public void tick() {
        if (!started) return;
        updateMicWanted();
    }
}
