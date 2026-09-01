package com.theo.voicecast.client;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.config.ClientVoiceConfig;
import com.theo.voicecast.net.VoiceCastNetwork;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static dev.architectury.event.events.client.ClientCommandRegistrationEvent.literal;
import static dev.architectury.event.events.client.ClientCommandRegistrationEvent.argument;
import com.mojang.brigadier.arguments.StringArgumentType;

/**
 * Client-side engine preference handling. The player's chosen recognizer
 * (Vosk words or IPA phonemes) is stored locally and sent to the server, which
 * lazily loads the matching shared model and builds the per-player recognizer.
 */
public final class EnginePicker {
    private static String lastSent = "";

    private EnginePicker() {}

    public static String preferred() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return ClientVoiceConfig.ENGINE_VOSK;
        return ClientVoiceConfig.load(mc.gameDirectory.toPath()).engine;
    }

    private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getConnection() != null;
    }

    /** Persist + send the engine choice to the server (no-op off-world). */
    public static void request(String engine) {
        if (!ClientVoiceConfig.isValidEngine(engine)) return;
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

    /** On (re)joining a world, (re)send the current preference. */
    public static void onJoin() {
        if (!inWorld()) return;
        String engine = preferred();
        VoiceCastNetwork.sendSelect(engine);
        lastSent = engine;
    }

    public static void openScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new EngineSelectScreen()));
    }

    static void feedback(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(text, false);
    }

    /** Register the /voicecast client command (idempotent). */
    public static void registerCommands() {
        ClientCommandRegistrationEvent.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("voicecast")
                    .then(literal("settings").executes(ctx -> {
                        openScreen();
                        return 0;
                    }))
                    .then(literal("engine")
                            .then(argument("engine", StringArgumentType.word()).executes(ctx -> {
                                String eng = StringArgumentType.getString(ctx, "engine");
                                String norm = normalize(eng);
                                if (norm == null) {
                                    feedback(Component.translatable("voicecast.engine.cmd.unknown", eng));
                                    return 0;
                                }
                                request(norm);
                                feedback(Component.translatable("voicecast.engine.cmd.set", norm));
                                return 1;
                            }))
                            .executes(ctx -> {
                                feedback(Component.translatable("voicecast.engine.cmd.current", preferred()));
                                return 0;
                            })));
        });
    }

    /** Accept vosk/ipa aliases incl. the en-us language tag. */
    private static String normalize(String s) {
        return ClientVoiceConfig.normalize(s);
    }
}
