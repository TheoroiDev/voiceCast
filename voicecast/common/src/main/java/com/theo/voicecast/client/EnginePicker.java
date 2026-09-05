package com.theo.voicecast.client;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.config.ClientVoiceConfig;
import com.theo.voicecast.model.ModelConfig;
import com.theo.voicecast.net.VoiceCastNetwork;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-side engine preference handling. The player's chosen recognizer is
 * stored locally and sent to the server, which lazily loads the matching
 * shared model and builds the per-player recognizer.
 *
 * <p>Engine requests accept model names from the models.json v2 catalog
 * (one model = one engine), two-letter language codes and common language
 * names — a language selects the <em>first declared</em> catalog model
 * supporting it. The platform modules register the {@code /voicecast} client
 * command tree (settings / engine / verbose / debugwav / status).
 */
public final class EnginePicker {
    private static String lastSent = "";

    private EnginePicker() {}

    /** The saved preference; empty means "catalog default". */
    public static String preferred() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return "";
        return ClientVoiceConfig.load(mc.gameDirectory.toPath()).engine;
    }

    /** The effective engine: saved preference when it resolves, else the catalog default. */
    public static String effectiveEngine() {
        String saved = preferred();
        if (saved.isEmpty()) return catalogDefault();
        String resolved = resolveRequested(saved);
        return resolved != null ? resolved : catalogDefault();
    }

    private static String catalogDefault() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return ClientVoiceConfig.ENGINE_NOOP;
        ModelConfig catalog = ModelConfig.load(mc.gameDirectory.toPath());
        ModelConfig.ModelEntry zh = catalog.resolveModel("zh");
        if (zh != null) return zh.id();
        var ids = catalog.modelIds();
        return ids.isEmpty() ? ClientVoiceConfig.ENGINE_NOOP : ids.get(0);
    }

    /**
     * Resolve an engine request: exact catalog model name, then a two-letter
     * language code / language name mapped to the first declared model
     * supporting it, then the {@code noop} pseudo-engine. Null when unknown.
     */
    public static String resolveRequested(String arg) {
        if (arg == null || arg.isBlank()) return null;
        String a = arg.trim().toLowerCase(java.util.Locale.ROOT);
        if (ClientVoiceConfig.ENGINE_NOOP.equals(a)) return ClientVoiceConfig.ENGINE_NOOP;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return null;
        ModelConfig catalog = ModelConfig.load(mc.gameDirectory.toPath());
        ModelConfig.ModelEntry m = catalog.resolveModel(a);
        return m == null ? null : m.id();
    }

    private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getConnection() != null;
    }

    /** Persist + send the engine choice to the server (no-op off-world). */
    public static void request(String engine) {
        if (engine == null || engine.isBlank()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.gameDirectory != null) {
            Path dir = mc.gameDirectory.toPath();
            ClientVoiceConfig cfg = ClientVoiceConfig.load(dir);
            cfg.engine = engine;
            cfg.save(dir);
        }
        if (!inWorld()) {
            VoiceCast.LOGGER.info("Engine preference saved ({}); will apply on join", engine);
            return;
        }
        VoiceCastNetwork.sendSelect(engine);
        lastSent = engine;
        VoiceCast.LOGGER.info("Requested recognizer engine: {}", engine);
    }

    /** Full {@code /voicecast engine <arg>} handling: resolve, persist + send,
     *  and show the result in chat. Returns false when the argument is unknown. */
    public static boolean requestResolved(String arg) {
        String norm = resolveRequested(arg);
        if (norm == null) {
            feedback(Component.translatable("voicecast.engine.cmd.unknown", arg));
            return false;
        }
        request(norm);
        feedback(Component.translatable("voicecast.engine.cmd.set", norm));
        return true;
    }

    /** Chat feedback for bare {@code /voicecast engine} (effective engine). */
    public static void currentEngineFeedback() {
        feedback(Component.translatable("voicecast.engine.cmd.current", effectiveEngine()));
    }

    /** On (re)joining a world, (re)send the current preference. Must never
     *  throw: this runs inside the login packet handler, where an exception
     *  would break sibling hooks (e.g. fabric's client command dispatcher
     *  setup, which is what killed /voicecast before this was guarded). */
    public static void onJoin() {
        try {
            if (!inWorld()) return;
            String engine = effectiveEngine();
            VoiceCastNetwork.sendSelect(engine);
            lastSent = engine;
        } catch (Throwable t) {
            VoiceCast.LOGGER.warn("Failed to send engine preference on join (will retry on next selection)", t);
        }
    }

    /** Open the engine selection screen (client thread safe). */
    public static void openScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new EngineSelectScreen()));
    }

    static void feedback(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(text, false);
    }
}
