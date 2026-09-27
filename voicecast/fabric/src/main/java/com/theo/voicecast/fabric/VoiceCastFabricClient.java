package com.theo.voicecast.fabric;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.client.EnginePicker;
import com.theo.voicecast.client.VoiceCastClient;
import com.theo.voicecast.client.VoiceCastClientDebug;
import com.theo.voicecast.client.hud.VoiceCastHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Client wiring on Fabric. PTT is now driven externally (WizardReal: hold a
 * staff and right-click), so no key binding is registered here. We init
 * the client, tick the pipeline, render the waveform HUD, and register the
 * client command tree ({@code /voicecast settings|verbose|debugwav|status|
 * engine [id|language]}, voiceCast#28).
 */
public final class VoiceCastFabricClient implements ClientModInitializer {
    private static boolean initialized;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!initialized) {
                initialized = true;
                VoiceCastClient.INSTANCE.init();
            }
            VoiceCastClient.INSTANCE.tick();
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) ->
                VoiceCastHud.INSTANCE.render(drawContext));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("voicecast")
                        .then(ClientCommandManager.literal("settings")
                                .executes(ctx -> {
                                    EnginePicker.openScreen();
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(ClientCommandManager.literal("verbose")
                                .executes(ctx -> feedback(ctx.getSource(), VoiceCastClientDebug.toggleVerbose())))
                        .then(ClientCommandManager.literal("debugwav")
                                .executes(ctx -> feedback(ctx.getSource(), VoiceCastClientDebug.toggleDebugWav())))
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> feedback(ctx.getSource(), VoiceCastClientDebug.status())))
                        .then(ClientCommandManager.literal("licenses")
                                .executes(ctx -> feedback(ctx.getSource(), VoiceCastClientDebug.licenses()))
                                .then(ClientCommandManager.literal("accept")
                                        .executes(ctx -> feedback(ctx.getSource(), VoiceCastClientDebug.licensesAccept()))))
                        .then(ClientCommandManager.literal("engine")
                                .executes(ctx -> {
                                    EnginePicker.currentEngineFeedback();
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(ClientCommandManager.literal("list")
                                        .executes(ctx -> feedback(ctx.getSource(), VoiceCastClientDebug.engines())))
                                .then(ClientCommandManager.argument("engine", StringArgumentType.word())
                                        .executes(ctx -> {
                                            EnginePicker.requestResolved(
                                                    StringArgumentType.getString(ctx, "engine"));
                                            return Command.SINGLE_SUCCESS;
                                        })))));
    }

    private static int feedback(FabricClientCommandSource source, List<String> lines) {
        for (String line : lines) {
            source.sendFeedback(Component.literal(line));
        }
        return Command.SINGLE_SUCCESS;
    }
}
