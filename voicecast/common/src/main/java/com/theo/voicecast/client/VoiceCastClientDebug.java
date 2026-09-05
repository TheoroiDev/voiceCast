package com.theo.voicecast.client;

import com.theo.voicecast.config.VoiceCastConfig;
import com.theo.voicecast.model.ModelConfig;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side debug state for the {@code /voicecast verbose|debugwav|status|
 * engine list} commands (voiceCast#28). Pure logic — each platform module
 * registers its own client command tree (Fabric client-command API / Forge
 * {@code RegisterClientCommandsEvent}) and renders the returned lines.
 */
public final class VoiceCastClientDebug {
    private VoiceCastClientDebug() {}

    public static List<String> toggleVerbose() {
        VoiceCastConfig.INSTANCE.verboseLogging = !VoiceCastConfig.INSTANCE.verboseLogging;
        return lines("verbose=" + VoiceCastConfig.INSTANCE.verboseLogging);
    }

    public static List<String> toggleDebugWav() {
        VoiceCastConfig.INSTANCE.saveDebugWav = !VoiceCastConfig.INSTANCE.saveDebugWav;
        return lines("debugWav=" + VoiceCastConfig.INSTANCE.saveDebugWav);
    }

    public static List<String> status() {
        VoiceCastClient client = VoiceCastClient.INSTANCE;
        return lines("enabled=" + client.isEnabled()
                + ", pttHeld=" + client.isPttHeld()
                + ", micWanted=" + client.isMicWanted()
                + ", verbose=" + VoiceCastConfig.INSTANCE.verboseLogging
                + ", debugWav=" + VoiceCastConfig.INSTANCE.saveDebugWav);
    }

    public static List<String> engines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return lines("models: (catalog unavailable)");
        ModelConfig catalog = ModelConfig.load(mc.gameDirectory.toPath());
        return lines("models: " + String.join(", ", catalog.modelIds()));
    }

    private static List<String> lines(String status) {
        List<String> out = new ArrayList<>(1);
        out.add("voicecast client: " + status);
        return out;
    }
}
