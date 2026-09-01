package com.theo.voicecast.forge;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.client.VoiceCastClient;
import com.theo.voicecast.client.hud.VoiceCastHud;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client wiring on Forge. PTT is now driven externally (WizardReal: hold a
 * staff and right-click), so no key binding is registered here. We only init
 * the client, tick the pipeline, and render the waveform HUD.
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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!initialized) {
            initialized = true;
            VoiceCastClient.INSTANCE.init();
        }
        VoiceCastClient.INSTANCE.tick();
    }
}
