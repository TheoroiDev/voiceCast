package com.theo.voicecast.forge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.client.EnginePicker;
import com.theo.voicecast.client.VoiceCastClient;
import com.theo.voicecast.client.VoiceCastClientDebug;
import com.theo.voicecast.client.hud.VoiceCastHud;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Client wiring on Forge. PTT is now driven externally (WizardReal: hold a
 * staff and right-click), so no key binding is registered here. We init
 * the client, tick the pipeline, render the waveform HUD, and register the
 * client command tree ({@code /voicecast settings|verbose|debugwav|status|
 * engine [id|language]}, voiceCast#28).
 */
@Mod.EventBusSubscriber(modid = VoiceCast.MOD_ID, value = Dist.CLIENT)
public final class VoiceCastForgeClient {
    private static boolean initialized;

    private VoiceCastForgeClient() {}

    @Mod.EventBusSubscriber(modid = VoiceCast.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("voicecast_status",
                    (gui, graphics, partialTick, width, height) ->
                            VoiceCastHud.INSTANCE.render(graphics));
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("voicecast")
                .then(Commands.literal("settings")
                        .executes(ctx -> {
                            EnginePicker.openScreen();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("verbose")
                        .executes(ctx -> send(ctx.getSource(), VoiceCastClientDebug.toggleVerbose())))
                .then(Commands.literal("debugwav")
                        .executes(ctx -> send(ctx.getSource(), VoiceCastClientDebug.toggleDebugWav())))
                .then(Commands.literal("status")
                        .executes(ctx -> send(ctx.getSource(), VoiceCastClientDebug.status())))
                .then(Commands.literal("engine")
                        .executes(ctx -> {
                            EnginePicker.currentEngineFeedback();
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("list")
                                .executes(ctx -> send(ctx.getSource(), VoiceCastClientDebug.engines())))
                        .then(Commands.argument("engine", StringArgumentType.word())
                                .executes(ctx -> {
                                    EnginePicker.requestResolved(
                                            StringArgumentType.getString(ctx, "engine"));
                                    return Command.SINGLE_SUCCESS;
                                }))));
    }

    private static int send(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!initialized) {
            initialized = true;
            VoiceCastClient.INSTANCE.init();
        }
        VoiceCastClient.INSTANCE.tick();
    }
}
