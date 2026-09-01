package com.theo.voicecast.forge;

import com.theo.voicecast.forge.client.VoiceCastForgeConfigClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Bootstrap for the native Mods-list config button on Forge. This class
 * contains NO client-only references (no Screen/MinecraftClient) so it is safe
 * to load on a dedicated server; it delegates to a client-only class that is
 * only loaded when {@code dist == CLIENT}.
 */
final class VoiceCastForgeConfig {
    private VoiceCastForgeConfig() {}

    static void register() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        VoiceCastForgeConfigClient.register();
    }
}
