package com.theo.voicecast.fabric;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.compat.ModDetection;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class VoiceCastFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ModDetection.init(id -> FabricLoader.getInstance().isModLoaded(id));
        VoiceCast.init();
    }
}
