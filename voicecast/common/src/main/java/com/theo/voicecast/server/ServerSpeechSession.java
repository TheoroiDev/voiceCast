package com.theo.voicecast.server;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.api.event.ServerRecognitionFinalEvent;
import com.theo.voicecast.audio.OpusAudioCodec;
import com.theo.voicecast.api.engine.EngineFamilies;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
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
    private SpeechRecognizer recognizer;
    private volatile SessionVocabulary vocabulary = SessionVocabulary.EMPTY;
    // Casting-time mode (0.5.0, issue #30/D-15): null = no declaration, the
    // pre-#30 full-vocabulary routing. Declared by the game-side integration
    // (free casting = OPEN, ladder chant = CHANT_CONFIRM + the spell).
    private volatile CastMode castMode;
    private volatile List<String> castSpellIds = java.util.List.of();
    private long lastFrameMs;
    private boolean active; // recognizer built and live for the current engine
    private String activeEngine; // engine the current recognizer was built for

    // Sliding-window frame-rate accounting (touched on the server main thread).
    private final Deque<Long> frameArrivals = new ArrayDeque<>();
    private long lastThrottleWarnMs;

    /** R2 F-B2: frame-rate native reloads on a failing recognizer build are
     *  the silent-death-loop failure mode — build attempts are gated by this
     *  backoff once a build has failed (≥30s, doubling, 5 min cap). */
    private final BuildBackoff buildBackoff = new BuildBackoff();

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

    /** Player picked an engine; request lazy server load and (re)build when ready.
     *  Explicit user action — the R2 F-B2/F-B4 backoffs are reset so the retry
     *  is immediate. */
    void requestEngine(String engineId) {
        this.engine = engineId;
        buildBackoff.force();
        VoiceCastServer.INSTANCE.requestEngine(engineId, true);
        // Rebuild even if a recognizer for a (different) engine is already active;
        // ensureReady detects the change via activeEngine.
        worker.submit(() -> {
            active = false;
            activeEngine = null;
            ensureReady();
        });
    }

    void setVocabulary(SessionVocabulary vocab) {
        this.vocabulary = vocab == null ? SessionVocabulary.EMPTY : vocab;
        submit(() -> {
            SpeechRecognizer r = recognizer();
            if (r != null) r.setVocabulary(routedVocabulary());
        });
    }

    /**
     * Declare the player's casting-time mode (issue #30/D-1): the recognizer
     * grammar is re-routed to the mode's candidate set. {@code null} mode =
     * no declaration (full vocabulary, the pre-#30 behavior). Safe from any
     * thread; the re-route runs on the session worker — after {@link #dispose()}
     * the submission is silently dropped (same REE-tolerant path as
     * {@link #onAudioFrame}), never thrown back at the game-thread caller.
     */
    void setCastMode(CastMode mode, Collection<String> spellIds) {
        this.castMode = mode;
        this.castSpellIds = spellIds == null ? java.util.List.of() : List.copyOf(spellIds);
        submit(() -> {
            SpeechRecognizer r = recognizer();
            if (r != null) r.setVocabulary(routedVocabulary());
        });
    }

    /** Vocabulary routed for this session: the cast-mode candidate set
     *  (0.5.0) intersected with the engine's language buckets (D-A2).
     *  Language-agnostic engines (zipa-ipa, noop) are never mode-routed —
     *  the IPA line stays full-vocabulary (issue #30 D5). */
    private SessionVocabulary routedVocabulary() {
        List<String> languages = VoiceCastServer.INSTANCE.engineLanguages(engine);
        if (languages.isEmpty()) return vocabulary;
        Collection<SessionVocabulary.Entry> modeRouted =
                VocabularyRouter.forSpells(vocabulary.entries(), castMode, castSpellIds);
        Collection<SessionVocabulary.Entry> routed = VocabularyRouter.forLanguages(modeRouted, languages);
        return routed == vocabulary.entries() ? vocabulary : new SessionVocabulary(routed);
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
        // R2 F-B2: a failed build (broken model dir past the file probe) must
        // not retry at frame rate — frames are dropped until the backoff
        // clears (or the player re-selects the engine, which forces a retry).
        if (!buildBackoff.allowed(System.currentTimeMillis())) return;
        buildRecognizer();
    }

    private void buildRecognizer() {
        disposeRecognizer();
        // R2 F-B3: the qwen3 native load happens HERE (tens of seconds) after
        // the engine-level READY broadcast already reached the player — without
        // this the HUD silently falls back to idle during the whole load.
        VoiceCastServer.INSTANCE.sendState(player, RecognizerState.LOADING,
                "voicecast.state.session_loading", engine);
        try {
            SpeechRecognizer r = VoiceCastServer.INSTANCE.createRecognizer(engine);
            r.setResultSink(this::onResult);
            // configure seeds the ROUTED vocabulary before start (single
            // setVocabulary per build — sherpa bakes hotwords at construction,
            // a post-start set would stop+rebuild the recognizer).
            boolean started = VoiceCastServer.INSTANCE.configure(r, engine, routedVocabulary());
            if (!started || !r.isActive()) {
                // R2 F-B2: the failure is player-visible (ERROR state) and the
                // next attempt waits out the backoff instead of frame-rate
                // reloading the native session.
                try { r.stop(); } catch (Throwable ignored) {}
                buildBackoff.onFailure(System.currentTimeMillis());
                VoiceCast.LOGGER.warn("Recognizer start failed for {} ({}); retrying after backoff",
                        playerName(), engine);
                VoiceCastServer.INSTANCE.sendState(player, RecognizerState.ERROR,
                        "voicecast.state.error", engine);
                return;
            }
            recognizer = r;
            active = true;
            activeEngine = engine;
            buildBackoff.onSuccess();
            VoiceCastServer.INSTANCE.sendState(player, RecognizerState.READY, "voicecast.state.ready", engine);
            VoiceCast.LOGGER.info("Speech session ready for {} ({})", playerName(), engine);
        } catch (Throwable t) {
            recognizer = null;
            active = false;
            buildBackoff.onFailure(System.currentTimeMillis());
            VoiceCast.LOGGER.warn("Failed to build recognizer for {} ({})", playerName(), engine, t);
            VoiceCastServer.INSTANCE.sendState(player, RecognizerState.ERROR,
                    "voicecast.state.error", String.valueOf(t.getMessage()));
        }
    }

    /** Null-safe display name for logs (the player is never null in production). */
    private String playerName() {
        return player == null ? "?" : player.getName().getString();
    }

    private void disposeRecognizer() {
        active = false;
        activeEngine = null;
        if (recognizer != null) { try { recognizer.stop(); } catch (Throwable ignored) {} }
        recognizer = null;
    }

    private SpeechRecognizer recognizer() {
        return recognizer;
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
        // R2 F-B7: partials are HUD-only (decision == null) — never feed one
        // into the final-event sink, or an addon engine that emits partials
        // would push half an utterance into the game's chant handling.
        if (result.decision() == null) return;
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
