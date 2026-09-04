package com.theo.voicecast.server;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.RecognizerRegistry;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.config.ServerConfig;
import com.theo.voicecast.api.engine.EngineFamilies;
import com.theo.voicecast.api.engine.EngineSpec;
import com.theo.voicecast.engine.IpaPhonemeRecognizer;
import com.theo.voicecast.engine.IpaShared;
import com.theo.voicecast.model.IpaModel;
import com.theo.voicecast.model.ModelConfig;
import com.theo.voicecast.model.ModelManager;
import com.theo.voicecast.model.SherpaModel;
import com.theo.voicecast.net.VoiceCastNetwork;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-side orchestration. Runs recognizers on the server; each speaking
 * player gets a {@link ServerSpeechSession}. Engines (Vosk word models — one
 * per language — and the IPA phoneme model) are loaded lazily and shared: a
 * model is downloaded/loaded once when the first player selects an engine using
 * it, and reused by every session of that engine. Different players can use
 * different engines at the same time.
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
    private String defaultEngine = "sherpa-zh-en";

    private final Map<String, EngineState> engineStates = new ConcurrentHashMap<>();
    private final Map<UUID, ServerSpeechSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> deniedNotified = ConcurrentHashMap.newKeySet();
    private volatile Collection<Pronunciation> vocabulary = java.util.List.of();
    private volatile com.theo.voicecast.api.AccessCheck accessCheck;
    private ScheduledExecutorService scheduler;

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

    // ---- /voicecast server command surface (voiceCast#29) ----------------

    /** Master switch as currently configured. */
    public boolean enabled() { return config == null || config.enabled; }

    /** Live session count (for /voicecast status). */
    public int sessionCount() { return sessions.size(); }

    /** Engine id -> state name snapshot (for /voicecast status). */
    public Map<String, String> engineStateSnapshot() {
        Map<String, String> out = new java.util.HashMap<>();
        engineStates.forEach((k, v) -> out.put(k, v.name()));
        return out;
    }

    /** Whether an id is a registered engine (registry + noop). */
    public boolean isValidEngineId(String id) {
        return "noop".equals(id) || RecognizerRegistry.ids().contains(id);
    }

    /** Runtime default-engine change (persists to voicecast.toml). */
    public synchronized void setDefaultEngine(String engine) {
        if (config == null || runDir == null) return;
        this.config.engine = engine;
        this.config.save(runDir);
        this.defaultEngine = engine;
        VoiceCast.LOGGER.info("/voicecast: default engine set to '{}'", engine);
    }

    /** Runtime master-switch change (persists; broadcasts the disabled state). */
    public synchronized void setEnabled(boolean value) {
        if (config == null || runDir == null) return;
        this.config.enabled = value;
        this.config.save(runDir);
        if (!value) broadcastState(RecognizerState.ERROR, "voicecast.state.disabled");
        VoiceCast.LOGGER.info("/voicecast: enabled={}", value);
    }

    /** Add a UUID to the {@code [players] whitelist}; false when already present. */
    public synchronized boolean whitelistAdd(UUID uuid) {
        if (config == null || runDir == null) return false;
        List<String> updated = new ArrayList<>(config.whitelist);
        boolean changed = updated.add(uuid.toString());
        if (changed) { config.whitelist = List.copyOf(updated); config.save(runDir); }
        return changed;
    }

    /** Remove a UUID from the {@code [players] whitelist}; false when absent. */
    public synchronized boolean whitelistRemove(UUID uuid) {
        if (config == null || runDir == null) return false;
        List<String> updated = new ArrayList<>(config.whitelist);
        boolean changed = updated.remove(uuid.toString());
        if (changed) { config.whitelist = List.copyOf(updated); config.save(runDir); }
        return changed;
    }

    /** Raw whitelist entries (for /voicecast whitelist list). */
    public List<String> whitelistEntries() {
        return config == null ? List.of() : config.whitelist;
    }

    /** Re-read voicecast.toml (default engine / enabled / whitelist / limits). */
    public synchronized void reloadConfig() {
        if (server == null || runDir == null) return;
        this.config = ServerConfig.load(runDir);
        this.defaultEngine = config.engine;
        VoiceCast.LOGGER.info("/voicecast: config reloaded (default engine '{}', enabled={})",
                defaultEngine, config.enabled);
        if (!config.enabled) broadcastState(RecognizerState.ERROR, "voicecast.state.disabled");
    }

    /** Whether the engine's configured model is a loose-files model (e.g. the IPA ONNX). */
    boolean isLooseFilesEngine(String engine) {
        if (modelConfig == null) return "ipa-phonemes".equals(engine);
        ModelConfig.ModelEntry e = modelConfig.modelForEngine(engine);
        if (e != null) return ModelConfig.KIND_LOOSE_FILES.equals(e.kind());
        return "ipa-phonemes".equals(engine); // legacy fallback before models.json existed
    }

    /** Language buckets for an engine (two-letter codes; empty = language-agnostic). */
    public List<String> engineLanguages(String engine) {
        if (modelConfig != null) {
            List<String> declared = modelConfig.languagesForEngine(engine);
            if (!declared.isEmpty()) return declared;
            String single = modelConfig.languageForEngine(engine);
            if (single != null && !single.isBlank()) return List.of(single);
        }
        return List.of();
    }

    /**
     * Resolve the model directory for an engine (download/extract when needed).
     * Dispatch by the configured model kind — vosk-archive is no longer a
     * builtin family (vosk removed in 0.4.0).
     */
    Path resolveEngineModelDir(String engine) throws Exception, InterruptedException {
        ModelConfig.ModelEntry entry = modelConfig == null ? null : modelConfig.modelForEngine(engine);
        String kind = entry == null ? null : entry.kind();
        if (ModelConfig.KIND_LOOSE_FILES.equals(kind)) {
            return IpaModel.directory(runDir, entry != null ? entry.id() : ModelConfig.MODEL_IPA);
        }
        if (ModelConfig.KIND_SHERPA_ARCHIVE.equals(kind)) {
            return SherpaModel.resolveOrDownload(runDir, modelConfig, entry,
                    (done, total) -> broadcastState(RecognizerState.LOADING,
                            "voicecast.state.downloading_vosk", SherpaModel.describeSize(done)));
        }
        throw new java.io.IOException("Unsupported model kind for engine '"
                + engine + "': " + kind);
    }

    /** Family-lookup recognizer creation for a session (voiceCast#42 §2.3). */
    SpeechRecognizer createRecognizer(String engine) {
        String type = modelConfig == null ? null : modelConfig.typeForEngine(engine);
        if (type == null) type = isLooseFilesEngine(engine) ? "ipa" : null;
        EngineSpec.RecognizerFactory factory = type == null ? null : EngineFamilies.get(type);
        if (factory == null) {
            throw new IllegalStateException("Unsupported engine family for '" + engine
                    + "' (type=" + type + "; registered families: "
                    + EngineFamilies.types() + ")");
        }
        try {
            Path modelDir = resolveEngineModelDir(engine);
            EngineSpec spec = new EngineSpec(type, engine, modelDir,
                    modelConfig != null ? modelConfig.languagesForEngine(engine) : List.of(),
                    modelConfig != null ? modelConfig.optionsForEngine(engine) : Map.of());
            return factory.create(spec);
        } catch (Exception e) {
            throw new RuntimeException("Recognizer creation failed for engine '" + engine + "'", e);
        }
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
            // Dispatch by the configured model kind (models.json): loose-files
            // (IPA ONNX) or sherpa-archive (tar.bz2 with tokens + onnx files).
            if (isLooseFilesEngine(engine)) {
                ModelConfig.ModelEntry entry = modelConfig.modelForEngine(engine);
                Path dir;
                if (config.autoDownload) {
                    if (entry == null) throw new java.io.IOException("No model configured for engine " + engine);
                    dir = IpaModel.resolveOrDownload(runDir, modelConfig, entry, (done, total) ->
                            broadcastState(RecognizerState.LOADING, "voicecast.state.downloading_ipa", SherpaModel.describeSize(done)));
                } else {
                    dir = IpaModel.directory(runDir, entry != null ? entry.id() : ModelConfig.MODEL_IPA);
                    if (!IpaModel.isValidModelDir(dir))
                        throw new java.io.IOException("IPA model missing and autoDownload=false");
                }
                IpaShared.getOrLoad(dir);
            } else if (modelConfig != null
                    && ModelConfig.KIND_SHERPA_ARCHIVE.equals(
                        modelConfig.entryKind(engine))) {
                ModelConfig.ModelEntry entry = modelConfig.modelForEngine(engine);
                if (config.autoDownload) {
                    if (entry == null) throw new java.io.IOException("No model configured for engine " + engine);
                    SherpaModel.resolveOrDownload(runDir, modelConfig, entry, (done, total) ->
                            broadcastState(RecognizerState.LOADING, "voicecast.state.downloading_vosk", SherpaModel.describeSize(done)));
                } else {
                    Path dir = runDir.resolve("config/voicecast/models").resolve(entry.id());
                    if (!SherpaModel.isValidModelDir(dir))
                        throw new java.io.IOException("sherpa model missing and autoDownload=false");
                }
            } else {
                throw new java.io.IOException("Unsupported model kind for engine '" + engine + "'");
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

    /**
     * Language bucket served by an engine (two-letter code, e.g. "en"/"zh"),
     * or null for language-agnostic engines (ipa-phonemes/noop). Resolution:
     * models.json {@code engines.<id>.language} first, then the unified
     * {@code vosk-<langcode>} id suffix (0.4.0 two-letter codes).
     */

    /** Build/start a recognizer for a session, wiring shared engine resources. */
    void configure(SpeechRecognizer r, String engine) {
        try {
            Path modelDir = resolveEngineModelDir(engine);
            SpeechOptions opts = new SpeechOptions(true, 0.65f, modelDir.toString(), true);
            // Route the vocabulary to this engine's language bucket(s) (D-A2):
            // the selected engine decides which aliases it can hear.
            r.setVocabulary(VocabularyRouter.forLanguages(vocabulary, engineLanguages(engine)));
            r.start(opts);
        } catch (Throwable t) {
            VoiceCast.LOGGER.warn("recognizer start failed for engine {}", engine, t);
        }
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
