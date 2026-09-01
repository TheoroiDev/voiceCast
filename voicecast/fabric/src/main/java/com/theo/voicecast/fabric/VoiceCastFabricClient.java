package com.theo.voicecast.fabric;

import com.theo.voicecast.client.VoiceCastClient;
import com.theo.voicecast.client.hud.VoiceCastHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * Client wiring on Fabric. PTT is now driven externally (WizardReal: hold a
 * staff and right-click), so no key binding is registered here. We only init
 * the client, tick the pipeline, and render the waveform HUD.
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
    }
}
