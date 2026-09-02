package com.theo.voicecast.server;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.api.event.ServerRecognitionFinalEvent;
import com.theo.voicecast.audio.OpusAudioCodec;
import com.theo.voicecast.engine.IpaPhonemeRecognizer;
import com.theo.voicecast.engine.VoskTextRecognizer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player server-side speech session. Owns a single-thread executor with a
 * bounded queue (stale audio frames are dropped rather than allowed to pile
 * up), an Opus decoder, and a recognizer for the player's chosen engine.
 *
 * <p>The recognizer is built lazily for the session's engine: while that engine
 * is downloading/loading the session stays inactive (frames dropped, LOADING
 * reported) and is activated via {@link #onEngineReady(String)} as soon as the
 * shared engine becomes available - no restart. Switching engine disposes the
 * old recognizer and builds the new one once ready.
 */
public final class ServerSpeechSession {
    private final ServerPlayer player;
    private final ThreadPoolExecutor worker;
    private final OpusAudioCodec codec = new OpusAudioCodec();
    private volatile String engine;
    private VoskTextRecognizer vosk;
    private IpaPhonemeRecognizer ipa;
    private Collection<Pronunciation> vocabulary = java.util.List.of();
    private long lastFrameMs;
    private boolean active; // recognizer built and live for the current engine
    private String activeEngine; // engine the current recognizer was built for

    // Sliding-window frame-rate accounting (touched on the server main thread).
    private final Deque<Long> frameArrivals = new ArrayDeque<>();
    private long lastThrottleWarnMs;

    ServerSpeechSession(ServerPlayer player, String engine) {
        this.player = player;
        this.engine = engine;
        this.worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "VoiceCast-Server-" + player.getName().getString());
                    t.setDaemon(true);
                    return t;
                },
                // Bounded backlog: silently drop NEW work when full (a stalled
                // session must never grow memory on the server). A dropped
                // FLUSH is recovered by the silence watchdog.
                new ThreadPoolExecutor.DiscardPolicy());
        // Let the idle core worker die after the 30s keepalive instead of
        // lingering forever; the thread is recreated on the next submission.
        worker.allowCoreThreadTimeOut(true);
        worker.submit(this::ensureReady);
    }

    /** A shared engine finished loading; if it is this session's engine, build now. */
    void onEngineReady(String readyEngine) {
        worker.submit(() -> {
            if (readyEngine.equals(engine)) ensureReady();
        });
    }

    /** Player picked an engine; request lazy server load and (re)build when ready. */
    void requestEngine(String engineId) {
        this.engine = engineId;
        VoiceCastServer.INSTANCE.requestEngine(engineId);
        // Rebuild even if a recognizer for a (different) engine is already active;
        // ensureReady detects the change via activeEngine.
        worker.submit(() -> {
            active = false;
            activeEngine = null;
            ensureReady();
        });
    }

    void setVocabulary(Collection<Pronunciation> vocab) {
        this.vocabulary = vocab == null ? java.util.List.of() : vocab;
        worker.submit(() -> {
            SpeechRecognizer r = recognizer();
            if (r != null) r.setVocabulary(this.vocabulary);
        });
    }

    private void ensureReady() {
        // Already running a recognizer for the requested engine? Nothing to do.
        if (active && engine.equals(activeEngine)) return;
        if (!VoiceCastServer.INSTANCE.isEngineReady(engine)) {
            // Trigger download/load if not already started; tell the client to wait.
            VoiceCastServer.INSTANCE.requestEngine(engine);
            VoiceCastServer.INSTANCE.sendState(player, RecognizerState.LOADING,
                    "voicecast.state.session_loading", engine);
            return;
        }
        buildRecognizer();
    }

    private void buildRecognizer() {
        disposeRecognizer();
        try {
            SpeechRecognizer r;
            if (VoiceCastServer.INSTANCE.isLooseFilesEngine(engine)) {
                ipa = new IpaPhonemeRecognizer();
                ipa.setResultSink(this::onResult);
                VoiceCastServer.INSTANCE.configure(ipa, engine);
                r = ipa;
            } else {
                vosk = new VoskTextRecognizer();
                VoiceCastServer.INSTANCE.attachSharedModel(vosk, engine);
                vosk.setResultSink(this::onResult);
                VoiceCastServer.INSTANCE.configure(vosk, engine);
                r = vosk;
            }
            if (r == null || !r.isActive()) {
                vosk = null;
                ipa = null;
                VoiceCast.LOGGER.warn("Recognizer not active for {} ({}), will retry",
                        player.getName().getString(), engine);
                return;
            }
            r.setVocabulary(vocabulary);
            active = true;
            activeEngine = engine;
            VoiceCastServer.INSTANCE.sendState(player, RecognizerState.READY, "voicecast.state.ready", engine);
            VoiceCast.LOGGER.info("Speech session ready for {} ({})", player.getName().getString(), engine);
        } catch (Throwable t) {
            vosk = null;
            ipa = null;
            active = false;
            VoiceCast.LOGGER.warn("Failed to build recognizer for {} ({})", player.getName().getString(), engine, t);
            VoiceCastServer.INSTANCE.sendState(player, RecognizerState.ERROR,
                    "voicecast.state.error", String.valueOf(t.getMessage()));
        }
    }

    private void disposeRecognizer() {
        active = false;
        activeEngine = null;
        try { if (vosk != null) vosk.stop(); } catch (Throwable ignored) {}
        try { if (ipa != null) ipa.stop(); } catch (Throwable ignored) {}
        vosk = null;
        ipa = null;
    }

    private SpeechRecognizer recognizer() {
        if (ipa != null) return ipa;
        return vosk;
    }

    void onAudioFrame(byte type, byte[] data) {
        if (data.length == 0) return;
        // Rate limit on the server main thread: sliding 1s window of accepted
        // frames, capped by the configured maxFramesPerSecond (normal clients
        // send 5 fps at 200 ms frames). Flooded sessions are throttled.
        long now = System.currentTimeMillis();
        Deque<Long> arrivals = frameArrivals;
        while (!arrivals.isEmpty() && now - arrivals.peekFirst() > 1000L) arrivals.pollFirst();
        int limit = VoiceCastServer.INSTANCE.maxFramesPerSecond();
        if (arrivals.size() >= limit) {
            if (now - lastThrottleWarnMs > 5000L) {
                lastThrottleWarnMs = now;
                VoiceCast.LOGGER.warn("Throttling audio from {} (>{} frames/s)",
                        player.getName().getString(), limit);
            }
            return;
        }
        arrivals.addLast(now);
        submit(() -> {
            try {
                if (type != com.theo.voicecast.net.VoiceCastNetwork.PAYLOAD_OPUS) return;
                ensureReady();
                SpeechRecognizer r = recognizer();
                if (r == null) return; // engine still loading; drop frame
                short[] pcm = codec.decode(data);
                r.acceptPcm(pcm, 0, pcm.length);
                lastFrameMs = System.currentTimeMillis();
            } catch (Throwable t) {
                VoiceCast.LOGGER.warn("session frame error", t);
            }
        });
    }

    void onControl(byte action) {
        submit(() -> {
            try {
                ensureReady();
                SpeechRecognizer r = recognizer();
                if (r == null) return;
                switch (action) {
                    case com.theo.voicecast.net.VoiceCastNetwork.ACT_FLUSH -> r.finishUtterance();
                    default -> { /* BEGIN/END are session lifecycle hints */ }
                }
            } catch (Throwable t) {
                VoiceCast.LOGGER.warn("session ctrl error", t);
            }
        });
    }

    /** Submit to the worker, tolerating a session that is already shutting down. */
    private void submit(Runnable task) {
        try {
            worker.submit(task);
        } catch (RejectedExecutionException ignored) {
            // session disposed concurrently
        }
    }

    /** Called on the worker thread by the recognizer sink when a result is ready. */
    private void onResult(RecognitionResult result) {
        if (result == null) return;
        VoiceCastServer.INSTANCE.sendTranscript(player, result);
        VoiceCastEvents.post(new ServerRecognitionFinalEvent(player, result));
    }

    /** Watchdog tick: flush if audio stopped mid-utterance (FLUSH packet loss). */
    void tickSilence(long nowMs) {
        boolean speaking = nowMs - lastFrameMs < 1200 && lastFrameMs != 0;
        if (speaking && nowMs - lastFrameMs >= 1000) {
            worker.submit(() -> {
                SpeechRecognizer r = recognizer();
                if (r != null) r.finishUtterance();
            });
        }
    }

    void dispose() {
        // Stop accepting work, let in-flight work finish (so a concurrently
        // running buildRecognizer cannot resurrect the recognizer after we
        // dispose it), then tear down native resources.
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        disposeRecognizer();
    }
}
