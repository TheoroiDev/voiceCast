package com.theo.voicecast.forge;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.compat.ModDetection;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

@Mod(VoiceCast.MOD_ID)
public final class VoiceCastForge {
    public VoiceCastForge() {
        ModDetection.init(id -> ModList.get().isLoaded(id));
        VoiceCast.init();
        VoiceCastForgeConfig.register();
    }
}
