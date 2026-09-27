package com.theo.voicecast.server;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.Calibration;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.config.ServerConfig;
import com.theo.voicecast.api.engine.EngineFamilies;
import com.theo.voicecast.api.engine.EngineSpec;
import com.theo.voicecast.engine.ZipaShared;
import com.theo.voicecast.model.ModelConfig;
import com.theo.voicecast.model.ModelManager;
import com.theo.voicecast.model.SherpaModel;
import com.theo.voicecast.model.ZipaModel;
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
    private String defaultEngine = "";

    private final Map<String, EngineState> engineStates = new ConcurrentHashMap<>();
    private final Map<UUID, ServerSpeechSession> sessions = new ConcurrentHashMap<>();
    /** Casting-time mode declarations (0.5.0, issue #30/D-1) for players
     *  whose session does not exist yet (declared before the first voice
     *  packet); applied when the session is created, dropped on quit. */
    private final Map<UUID, ModeDeclaration> castModes = new ConcurrentHashMap<>();
    private final Set<UUID> deniedNotified = ConcurrentHashMap.newKeySet();
    private volatile SessionVocabulary vocabulary = SessionVocabulary.EMPTY;
    private volatile com.theo.voicecast.api.AccessCheck accessCheck;
    private ScheduledExecutorService scheduler;

    public synchronized void start(MinecraftServer mc) {
        if (this.server != null) return;
        this.server = mc;
        this.runDir = mc.getServerDirectory().toPath();
        this.config = ServerConfig.load(runDir);
        this.modelConfig = ModelConfig.load(runDir);
        this.defaultEngine = resolveDefaultEngine();
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
        try { ZipaShared.shutdown(); } catch (Throwable ignored) {}
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

    /** Whether an id selects a catalog model (or the noop pseudo-engine). */
    public boolean isValidEngineId(String id) {
        return "noop".equals(id) || (modelConfig != null && modelConfig.resolveModel(id) != null);
    }

    /** Catalog engine ids in declaration order (for /voicecast engine list). */
    public List<String> catalogModelIds() {
        return modelConfig == null ? List.of() : modelConfig.engineIds();
    }

    /** One-line-per-engine catalog summary (id [lang=[...], family=...]). */
    public List<String> catalogSummary() {
        if (modelConfig == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String id : modelConfig.engineIds()) {
            out.add(id + " [lang=" + modelConfig.languagesFor(id)
                    + ", family=" + modelConfig.familyFor(id) + "]");
        }
        return out;
    }

    /**
     * Effective default engine: the configured {@code [server] defaultEngine}
     * (model name or language code) when it resolves against the catalog,
     * else the catalog default — the first declared model supporting zh, or
     * simply the first declared model.
     */
    private String resolveDefaultEngine() {
        String configured = config == null ? "" : config.engine.trim().toLowerCase(java.util.Locale.ROOT);
        if (modelConfig != null) {
            if (!configured.isEmpty() && !"noop".equals(configured)) {
                ModelConfig.ModelEntry m = modelConfig.resolveModel(configured);
                if (m != null) return m.id();
                VoiceCast.LOGGER.warn("Configured defaultEngine '{}' matches no catalog model; using the catalog default", configured);
            }
            ModelConfig.ModelEntry zh = modelConfig.resolveModel("zh");
            if (zh != null) return zh.id();
            List<String> ids = modelConfig.modelIds();
            if (!ids.isEmpty()) return ids.get(0);
        }
        return configured.isEmpty() ? "noop" : configured;
    }

    /** Runtime default-engine change (persists to voicecast.toml). */
    public synchronized void setDefaultEngine(String engine) {
        if (config == null || runDir == null) return;
        this.config.engine = engine;
        this.config.save(runDir);
        this.defaultEngine = resolveDefaultEngine();
        VoiceCast.LOGGER.info("/voicecast: default engine set to '{}' (resolved '{}')", engine, defaultEngine);
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
        this.modelConfig = ModelConfig.load(runDir);
        this.defaultEngine = resolveDefaultEngine();
        VoiceCast.LOGGER.info("/voicecast: config reloaded (default engine '{}', enabled={})",
                defaultEngine, config.enabled);
        if (!config.enabled) broadcastState(RecognizerState.ERROR, "voicecast.state.disabled");
    }

    /** Whether the engine's configured model is a loose-files model (e.g. the IPA ONNX). */
    boolean isLooseFilesEngine(String engine) {
        return modelConfig != null && ModelConfig.KIND_LOOSE_FILES.equals(modelConfig.kindFor(engine));
    }

    /** Language buckets for an engine (two-letter codes; empty = language-agnostic). */
    public List<String> engineLanguages(String engine) {
        return modelConfig == null ? List.of() : modelConfig.languagesFor(engine);
    }

    /**
     * Resolve the model directory for an engine (download/extract when needed).
     * Dispatch by the configured model kind — loose-files (ZIPA) or
     * sherpa-archive (tar.bz2 with tokens + onnx files).
     */
    Path resolveEngineModelDir(String engine) throws Exception, InterruptedException {
        ModelConfig.ModelEntry entry = modelConfig == null ? null : modelConfig.model(engine);
        String kind = entry == null ? null : entry.kind();
        if (ModelConfig.KIND_LOOSE_FILES.equals(kind)) {
            return ZipaModel.directory(runDir, entry.id());
        }
        if (ModelConfig.KIND_SHERPA_ARCHIVE.equals(kind)) {
            return SherpaModel.resolveOrDownload(runDir, modelConfig, entry,
                    (done, total) -> broadcastState(RecognizerState.LOADING,
                            "voicecast.state.downloading_model", SherpaModel.describeSize(done)));
        }
        throw new java.io.IOException("Unsupported model kind for engine '"
                + engine + "': " + kind);
    }

    /** Family-lookup recognizer creation for a session (voiceCast#42 §2.3). */
    SpeechRecognizer createRecognizer(String engine) {
        String family = modelConfig == null ? null : modelConfig.familyFor(engine);
        EngineSpec.RecognizerFactory factory = family == null ? null : EngineFamilies.get(family);
        if (factory == null) {
            throw new IllegalStateException("Unsupported engine family for '" + engine
                    + "' (family=" + family + "; registered families: "
                    + EngineFamilies.types() + ")");
        }
        try {
            Path modelDir = resolveEngineModelDir(engine);
            EngineSpec spec = new EngineSpec(family, engine, modelDir,
                    modelConfig != null ? modelConfig.languagesFor(engine) : List.of(),
                    modelConfig != null ? modelConfig.optionsFor(engine) : Map.of());
            return factory.create(spec);
        } catch (Exception e) {
            throw new RuntimeException("Recognizer creation failed for engine '" + engine + "'", e);
        }
    }

    /** Make an engine available (download + load), sharing resources server-wide. */
    public void requestEngine(String engine) {
        requestEngine(engine, false);
    }

    /**
     * Engine-level retry backoff (R2 F-B4): a FAILED engine used to be
     * re-requested on EVERY audio frame of a live session (auto-download on =
     * a continuous retry stream against the mirror). After a failure the
     * engine stays quiet for a doubling window (30 s → 5 min cap); an explicit
     * user selection ({@code userInitiated}) clears the penalty.
     */
    private static final long ENGINE_RETRY_BASE_MS = 30_000;
    private static final long ENGINE_RETRY_MAX_MS = 300_000;
    private final Map<String, Long> engineRetryNotBefore = new ConcurrentHashMap<>();
    private final Map<String, Integer> engineFailures = new ConcurrentHashMap<>();

    /** Request an engine load; {@code userInitiated} (player selection) overrides the F-B4 backoff. */
    public void requestEngine(String engine, boolean userInitiated) {
        if (config == null) return;
        EngineState state = engineStates.getOrDefault(engine, EngineState.UNLOADED);
        if (state == EngineState.READY || state == EngineState.DOWNLOADING) return;
        if (state == EngineState.FAILED && !userInitiated
                && System.currentTimeMillis() < engineRetryNotBefore.getOrDefault(engine, 0L)) {
            return; // F-B4: quiet period after a failure — no per-frame retry stream
        }
        if (userInitiated) {
            engineFailures.remove(engine);
            engineRetryNotBefore.remove(engine);
        }
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
            // (ZIPA ONNX + tokens) or sherpa-archive (tar.bz2 with onnx files).
            if (isLooseFilesEngine(engine)) {
                ModelConfig.ModelEntry entry = modelConfig.model(engine);
                Path dir;
                if (config.autoDownload) {
                    if (entry == null) throw new java.io.IOException("No model configured for engine " + engine);
                    dir = ZipaModel.resolveOrDownload(runDir, modelConfig, entry, (done, total) ->
                            broadcastState(RecognizerState.LOADING, "voicecast.state.downloading_ipa", SherpaModel.describeProgress(done, total)));
                } else {
                    dir = ZipaModel.directory(runDir, entry.id());
                    if (!ZipaModel.isValidModelDir(dir, entry))
                        throw new java.io.IOException("ZIPA model missing and autoDownload=false");
                }
                ZipaShared.getOrLoad(dir);
            } else if (modelConfig != null
                    && ModelConfig.KIND_SHERPA_ARCHIVE.equals(modelConfig.kindFor(engine))) {
                ModelConfig.ModelEntry entry = modelConfig.model(engine);
                if (config.autoDownload) {
                    if (entry == null) throw new java.io.IOException("No model configured for engine " + engine);
                    SherpaModel.resolveOrDownload(runDir, modelConfig, entry, (done, total) ->
                            broadcastState(RecognizerState.LOADING, "voicecast.state.downloading_model", SherpaModel.describeProgress(done, total)));
                } else {
                    Path dir = runDir.resolve("config/voicecast/models").resolve(entry.id());
                    if (!SherpaModel.isPlausiblyComplete(dir, entry))
                        throw new java.io.IOException("sherpa model missing or incomplete and autoDownload=false");
                }
            } else {
                throw new java.io.IOException("Unsupported model kind for engine '" + engine + "'");
            }
            engineStates.put(engine, EngineState.READY);
            engineFailures.remove(engine);
            engineRetryNotBefore.remove(engine);
            VoiceCast.LOGGER.info("Server voice engine ready: {}", engine);
            broadcastState(RecognizerState.READY, "voicecast.state.ready", engine);
            // Activate sessions that were waiting for this engine.
            sessions.values().forEach(s -> s.onEngineReady(engine));
        } catch (Throwable e) {
            VoiceCast.LOGGER.error("Server voice engine failed to start: {}", engine, e);
            engineStates.put(engine, EngineState.FAILED);
            int fails = engineFailures.merge(engine, 1, Integer::sum);
            engineRetryNotBefore.put(engine, System.currentTimeMillis()
                    + Math.min(ENGINE_RETRY_MAX_MS,
                            ENGINE_RETRY_BASE_MS << Math.min(fails - 1, 4)));
            broadcastState(RecognizerState.NO_MODEL, "voicecast.state.no_model", engine, String.valueOf(e.getMessage()));
        }
    }

    public void setVocabulary(SessionVocabulary vocab) {
        this.vocabulary = vocab == null ? SessionVocabulary.EMPTY : vocab;
        sessions.values().forEach(s -> s.setVocabulary(this.vocabulary));
    }

    /** Engine-calibration defaults for the semantic adjudication, from the
     *  {@code [match]} config section (semantic contract v2, C1b §0.3). */
    public Calibration calibration() {
        return config == null ? Calibration.DEFAULT : config.calibration();
    }

    /**
     * Language bucket served by an engine (two-letter code, e.g. "en"/"zh"),
     * or null for language-agnostic engines (zipa-ipa/noop). Resolution:
     * models.json {@code engines.<id>.language} first, then the unified
     * {@code vosk-<langcode>} id suffix (0.4.0 two-letter codes).
     */

    /** Build/start a recognizer for a session, wiring shared engine resources.
     *  The session passes its fully ROUTED vocabulary (cast-mode candidate set
     *  ∩ engine language buckets): it is seeded before {@link SpeechRecognizer#start}
     *  because grammar-based engines (sherpa) bake hotwords in at construction —
     *  a second setVocabulary after start would stop+rebuild the recognizer
     *  (double construction; the pre-0.5.x seed was also mode-blind, i.e. a
     *  "half-wrong" grammar that the routed call then had to replace).
     *
     *  @return whether the recognizer started. R2 F-B2: the failure is no
     *          longer swallowed here — the caller reports it to the player and
     *          backs off instead of frame-rate retrying the native load. */
    boolean configure(SpeechRecognizer r, String engine, SessionVocabulary routedVocabulary) {
        try {
            Path modelDir = resolveEngineModelDir(engine);
            SpeechOptions opts = new SpeechOptions(true, 0.65f, modelDir.toString(), true, calibration());
            r.setVocabulary(routedVocabulary);
            r.start(opts);
            return true;
        } catch (Throwable t) {
            VoiceCast.LOGGER.warn("recognizer start failed for engine {}", engine, t);
            return false;
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
        if (!"noop".equals(engine) && (modelConfig == null || modelConfig.resolveModel(engine) == null)) {
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
        castModes.remove(sp.getUUID());
    }

    /** Casting-time mode declaration (0.5.0, issue #30/D-1): the game-side
     *  integration (wizardreal) declares the player's routing mode through
     *  this entry — free casting = {@link CastMode#OPEN}, ladder chant =
     *  {@link CastMode#CHANT_CONFIRM} + the current spell. {@code mode ==
     *  null} clears the declaration (full vocabulary). The declaration
     *  outlives session rebuilds (engine switches; reconnects until quit)
     *  and reaches sessions that are created later. Safe from any thread. */
    public void setCastMode(Player player, CastMode mode, Collection<String> spellIds) {
        if (!(player instanceof ServerPlayer sp)) return;
        List<String> ids = spellIds == null ? List.of() : List.copyOf(spellIds);
        if (mode == null) {
            castModes.remove(sp.getUUID());
        } else {
            castModes.put(sp.getUUID(), new ModeDeclaration(mode, ids));
        }
        ServerSpeechSession s = sessions.get(sp.getUUID());
        if (s != null) s.setCastMode(mode, ids);
    }

    private record ModeDeclaration(CastMode mode, List<String> spellIds) {}

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
            ModeDeclaration mode = castModes.get(id);
            if (mode != null) s.setCastMode(mode.mode(), mode.spellIds());
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
        if (player == null) return; // defensive: nothing to send to (also the detached-test seam)
        NetworkManager.sendToPlayer(player, VoiceCastNetwork.CHANNEL_STATE,
                VoiceCastNetwork.encodeState(state.ordinal(), key, java.util.List.of(args)));
    }

    private static boolean isPartial(com.theo.voicecast.api.RecognitionResult result) {
        return result.decision() == null;
    }

    void sendTranscript(ServerPlayer player, com.theo.voicecast.api.RecognitionResult result) {
        NetworkManager.sendToPlayer(player, VoiceCastNetwork.CHANNEL_TRANSCRIPT,
                VoiceCastNetwork.encodeTranscript(isPartial(result), result.utteranceText(),
                        result.score(), result.startMs(),
                        result.decision() == null ? -1 : result.decision().ordinal(),
                        result.spellId()));
    }

    private void broadcastState(RecognizerState state, String key, String... args) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendState(p, state, key, args);
        }
    }
}
