package com.theo.voicecast.forge.client;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.client.EngineSelectScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;

/**
 * Client-only registration of the native Mods-list Config button. Loaded only
 * on the client (the caller guards the dist), so the dedicated server never
 * touches Screen/MinecraftClient classes.
 */
public final class VoiceCastForgeConfigClient {
    private VoiceCastForgeConfigClient() {}

    public static void register() {
        ModList.get().getModContainerById(VoiceCast.MOD_ID).ifPresent(container ->
                container.registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                                (minecraft, parent) -> new EngineSelectScreen(parent))));
    }
}
