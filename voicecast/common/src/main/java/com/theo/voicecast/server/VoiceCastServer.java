package com.theo.voicecast.server;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.config.ServerConfig;
import com.theo.voicecast.engine.IpaPhonemeRecognizer;
import com.theo.voicecast.engine.IpaShared;
import com.theo.voicecast.engine.VoskTextRecognizer;
import com.theo.voicecast.model.IpaModel;
import com.theo.voicecast.model.ModelConfig;
import com.theo.voicecast.model.ModelManager;
import com.theo.voicecast.model.VoskModel;
import com.theo.voicecast.net.VoiceCastNetwork;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.vosk.Model;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-side orchestration. Runs recognizers on the server; each speaking
 * player gets a {@link ServerSpeechSession}. Engines (Vosk word model and the
 * IPA phoneme model) are loaded lazily and shared: a model is downloaded/loaded
 * once when the first player selects it, and reused by every session. Different
 * players can use different engines at the same time.
 *
 * <p>All recognition runs on worker threads; the server main thread is only
 * used for downstream spell decisions.
 */
public enum VoiceCastServer {
    INSTANCE;

    private enum EngineState { UNLOADED, DOWNLOADING, READY, FAILED }

    private MinecraftServer server;
    private ServerConfig config;
    private ModelConfig modelConfig;
    private Path runDir;
    private String defaultEngine = "vosk-text";
    private volatile Model sharedVoskModel;

    private final Map<String, EngineState> engineStates = new ConcurrentHashMap<>();
    private final Map<UUID, ServerSpeechSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> deniedNotified = ConcurrentHashMap.newKeySet();
    private volatile Collection<Pronunciation> vocabulary = java.util.List.of();
    private volatile com.theo.voicecast.api.AccessCheck accessCheck;
    private ScheduledExecutorService scheduler;
    private final Object voskModelLock = new Object();

    public synchronized void start(MinecraftServer mc) {
        if (this.server != null) return;
        this.server = mc;
        this.runDir = mc.getServerDirectory().toPath();
        this.config = ServerConfig.load(runDir);
        this.modelConfig = ModelConfig.load(runDir);
        this.defaultEngine = config.engine;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VoiceCast-Server-Watchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::watchdogTick, 500, 500, TimeUnit.MILLISECONDS);
        VoiceCastNetwork.init();
        deniedNotified.clear();
        if (!config.enabled) {
            VoiceCast.LOGGER.info("VoiceCast is disabled by config ([server] enabled=false); models stay unloaded");
            broadcastState(RecognizerState.ERROR, "voicecast.state.disabled");
            return;
        }
        // Warm the default engine; others load on demand when a player selects them.
        requestEngine(defaultEngine);
    }

    public synchronized void stop() {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        sessions.values().forEach(ServerSpeechSession::dispose);
        sessions.clear();
        synchronized (voskModelLock) {
            if (sharedVoskModel != null) {
                try { sharedVoskModel.close(); } catch (Throwable ignored) {}
                sharedVoskModel = null;
            }
        }
        try { IpaShared.shutdown(); } catch (Throwable ignored) {}
        engineStates.clear();
        server = null;
    }

    public String defaultEngineId() { return defaultEngine; }
    /** Audio-frame rate cap applied per session (frames per second). */
    public int maxFramesPerSecond() { return config == null ? 15 : config.maxFramesPerSecond; }
    public boolean isEngineReady(String engine) {
        return engineStates.getOrDefault(engine, EngineState.UNLOADED) == EngineState.READY;
    }

    /** Whether the engine's configured model is a loose-files model (e.g. the IPA ONNX). */
    boolean isLooseFilesEngine(String engine) {
        if (modelConfig == null) return "ipa-phonemes".equals(engine);
        ModelConfig.ModelEntry e = modelConfig.modelForEngine(engine);
        if (e != null) return ModelConfig.KIND_LOOSE_FILES.equals(e.kind());
        return "ipa-phonemes".equals(engine); // legacy fallback before models.json existed
    }

    /** Make an engine available (download + load), sharing resources server-wide. */
    public void requestEngine(String engine) {
        if (config == null) return;
        EngineState state = engineStates.getOrDefault(engine, EngineState.UNLOADED);
        if (state == EngineState.READY || state == EngineState.DOWNLOADING) return;
        engineStates.put(engine, EngineState.DOWNLOADING);
        broadcastState(RecognizerState.LOADING, "voicecast.state.preparing", engine);
        final String eng = engine;
        Thread t = new Thread(() -> loadEngine(eng), "VoiceCast-EngineLoad-" + engine);
        t.setDaemon(true);
        t.start();
    }

    private void loadEngine(String engine) {
        try {
            // Dispatch by the configured model kind (models.json), not by engine id,
            // so any engine bound to a vosk archive or loose files just works.
            if (isLooseFilesEngine(engine)) {
                ModelConfig.ModelEntry entry = modelConfig.modelForEngine(engine);
                Path dir;
                if (config.autoDownload) {
                    if (entry == null) throw new java.io.IOException("No model configured for engine " + engine);
                    dir = IpaModel.resolveOrDownload(runDir, modelConfig, entry, (done, total) ->
                            broadcastState(RecognizerState.LOADING, "voicecast.state.downloading_ipa", VoskModel.describeSize(done)));
                } else {
                    dir = IpaModel.directory(runDir, entry != null ? entry.id() : ModelConfig.MODEL_IPA);
                    if (!IpaModel.isValidModelDir(dir))
                        throw new java.io.IOException("IPA model missing and autoDownload=false");
                }
                IpaShared.getOrLoad(dir);
            } else {
                ModelConfig.ModelEntry entry = modelConfig.modelForEngine(engine);
                Path dir;
                if (config.autoDownload) {
                    if (entry == null) throw new java.io.IOException("No model configured for engine " + engine);
                    dir = VoskModel.resolveOrDownload(runDir, modelConfig, entry, (done, total) ->
                            broadcastState(RecognizerState.LOADING, "voicecast.state.downloading_vosk", VoskModel.describeSize(done)));
                } else {
                    String modelId = entry != null ? entry.id() : VoskModel.DEFAULT_MODEL_ID;
                    dir = runDir.resolve("config/voicecast/models").resolve(modelId);
                    if (!VoskModel.isValidModelDir(dir))
                        throw new java.io.IOException("Vosk model missing and autoDownload=false");
                }
                try { org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS); } catch (Throwable ignored) {}
                synchronized (voskModelLock) {
                    if (sharedVoskModel == null) sharedVoskModel = new Model(dir.toAbsolutePath().toString());
                }
            }
            engineStates.put(engine, EngineState.READY);
            VoiceCast.LOGGER.info("Server voice engine ready: {}", engine);
            broadcastState(RecognizerState.READY, "voicecast.state.ready", engine);
            // Activate sessions that were waiting for this engine.
            sessions.values().forEach(s -> s.onEngineReady(engine));
        } catch (Throwable e) {
            VoiceCast.LOGGER.error("Server voice engine failed to start: {}", engine, e);
            engineStates.put(engine, EngineState.FAILED);
            broadcastState(RecognizerState.NO_MODEL, "voicecast.state.no_model", engine, String.valueOf(e.getMessage()));
        }
    }

    public void setVocabulary(Collection<Pronunciation> vocab) {
        this.vocabulary = vocab == null ? java.util.List.of() : java.util.List.copyOf(vocab);
        sessions.values().forEach(s -> s.setVocabulary(this.vocabulary));
    }

    /** Build/start a recognizer for a session, wiring shared engine resources. */
    void configure(SpeechRecognizer r, String engine) {
        try {
            String modelId = modelConfig != null ? modelConfig.modelIdForEngine(engine) : null;
            if (modelId == null) {
                modelId = isLooseFilesEngine(engine) ? ModelConfig.MODEL_IPA : VoskModel.DEFAULT_MODEL_ID;
            }
            Path modelPath = isLooseFilesEngine(engine)
                    ? IpaModel.directory(runDir, modelId)
                    : runDir.resolve("config/voicecast/models").resolve(modelId);
            SpeechOptions opts = new SpeechOptions(true, 0.65f, modelPath.toString(), true);
            r.setVocabulary(vocabulary);
            r.start(opts);
        } catch (Throwable t) {
            VoiceCast.LOGGER.warn("recognizer start failed for engine {}", engine, t);
        }
    }

    void attachSharedModel(VoskTextRecognizer r) {
        Model m = sharedVoskModel;
        if (m != null) r.useSharedModel(m);
    }

    // ---- packet handlers (called on server main thread via ctx.queue) ----

    public void onAudioFrame(Player player, byte type, byte[] data) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!allowed(sp)) {
            notifyDenied(sp);
            return;
        }
        if (sessions.get(sp.getUUID()) == null) session(sp);
        ServerSpeechSession s = sessions.get(sp.getUUID());
        s.onAudioFrame(type, data); // session drops frames until its engine is ready
    }

    public void onControl(Player player, byte action) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!allowed(sp)) {
            notifyDenied(sp);
            return;
        }
        if (sessions.get(sp.getUUID()) == null) session(sp);
        sessions.get(sp.getUUID()).onControl(action);
    }

    /** Player selected a recognizer engine; (re)build their session's recognizer. */
    public void onSelect(Player player, String engine) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!allowed(sp)) {
            notifyDenied(sp);
            return;
        }
        if (!com.theo.voicecast.config.ClientVoiceConfig.isValidEngine(engine)) {
            VoiceCast.LOGGER.warn("Ignoring invalid engine '{}' from {}", engine, player.getName().getString());
            return;
        }
        // Server-side whitelist: unlisted engines are refused outright (this
        // also prevents clients from triggering big model downloads).
        if (config != null && !config.engineAllowed(engine)) {
            VoiceCast.LOGGER.warn("Denied engine '{}' from {} (not in [engines].allowed)",
                    engine, player.getName().getString());
            sendState(sp, RecognizerState.ERROR, "voicecast.state.engine_not_allowed", engine);
            return;
        }
        ServerSpeechSession s = sessions.get(sp.getUUID());
        if (s == null) {
            s = session(sp);
            s.requestEngine(engine);
        } else {
            s.requestEngine(engine);
        }
    }

    public void onPlayerQuit(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        ServerSpeechSession s = sessions.remove(sp.getUUID());
        if (s != null) s.dispose();
    }

    /**
     * Install a pluggable access decision (permission-mod bridge). When set it
     * overrides the {@code [players]} whitelist; the {@code [server] enabled}
     * switch still applies.
     */
    public void setAccessCheck(com.theo.voicecast.api.AccessCheck hook) {
        this.accessCheck = hook;
    }

    /** Whether this player may stream audio at all (config policy + hook). */
    private boolean allowed(ServerPlayer player) {
        if (config == null) return false;
        AccessPolicy policy = new AccessPolicy(config.enabled, config.parsedWhitelist(), accessCheck);
        return policy.allows(player.getUUID());
    }

    /** Silent drop + one-time per-player notice for unauthorized senders. */
    private void notifyDenied(ServerPlayer player) {
        if (deniedNotified.add(player.getUUID())) {
            VoiceCast.LOGGER.warn("VoiceCast: denied voice access for {}", player.getName().getString());
            boolean disabled = config == null || !config.enabled;
            sendState(player, RecognizerState.ERROR,
                    disabled ? "voicecast.state.disabled" : "voicecast.state.denied");
        }
    }

    private ServerSpeechSession session(ServerPlayer player) {
        return sessions.computeIfAbsent(player.getUUID(), id -> {
            VoiceCast.LOGGER.info("Creating speech session for {}", player.getName().getString());
            ServerSpeechSession s = new ServerSpeechSession(player, defaultEngine);
            s.setVocabulary(vocabulary);
            return s;
        });
    }

    private void watchdogTick() {
        long now = System.currentTimeMillis();
        try {
            for (ServerSpeechSession s : sessions.values()) s.tickSilence(now);
        } catch (Throwable t) {
            VoiceCast.LOGGER.warn("watchdog error", t);
        }
    }

    // ---- S2C ----------------------------------------------------------

    void sendState(ServerPlayer player, RecognizerState state, String key, String... args) {
        NetworkManager.sendToPlayer(player, VoiceCastNetwork.CHANNEL_STATE,
                VoiceCastNetwork.encodeState(state.ordinal(), key, java.util.List.of(args)));
    }

    void sendTranscript(ServerPlayer player, com.theo.voicecast.api.RecognitionResult result) {
        NetworkManager.sendToPlayer(player, VoiceCastNetwork.CHANNEL_TRANSCRIPT,
                VoiceCastNetwork.encodeTranscript(result.partial(), result.text(),
                        result.confidence(), result.startMs()));
    }

    private void broadcastState(RecognizerState state, String key, String... args) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendState(p, state, key, args);
        }
    }
}
