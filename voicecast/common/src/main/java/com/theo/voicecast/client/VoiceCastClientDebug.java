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

    /** voiceCast#51 (refine 2026-09-27): the CLIENT consent surface — the
     *  denoiser downloads into the player's game dir, so its license gate
     *  reads {@code [client].acceptedLicenses}, NOT the server's
     *  {@code [modelLicenses]} list. List every catalog model with its
     *  license metadata and the client's acceptance state. */
    public static List<String> licenses() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return lines("models: (catalog unavailable)");
        java.nio.file.Path dir = mc.gameDirectory.toPath();
        ModelConfig catalog = ModelConfig.load(dir);
        var cfg = com.theo.voicecast.config.ClientVoiceConfig.load(dir);
        List<String> out = new ArrayList<>();
        out.add("client model licenses (the denoiser download is blocked until accepted):");
        for (String id : catalog.modelIds()) {
            var entry = catalog.model(id);
            String lic = entry == null || entry.license() == null
                    ? "unspecified"
                    : entry.license().name() + " <" + entry.license().url() + ">";
            out.add("  " + id + " — " + lic + "  [accepted=" + cfg.licenseAccepted(id) + "]");
        }
        out.add("accept all: /voicecast licenses accept (persists to [client].acceptedLicenses)");
        return out;
    }

    /** Accept every catalog model's license terms for THIS client (the
     *  denoiser path). Takes effect the next time the mic pipeline starts. */
    public static List<String> licensesAccept() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return lines("(catalog unavailable)");
        java.nio.file.Path dir = mc.gameDirectory.toPath();
        ModelConfig catalog = ModelConfig.load(dir);
        var cfg = com.theo.voicecast.config.ClientVoiceConfig.load(dir);
        for (String id : catalog.modelIds()) cfg.acceptLicense(dir, id);
        return lines("accepted client license terms for " + catalog.modelIds().size()
                + " model(s) — re-enabling the mic applies them");
    }

    private static List<String> lines(String status) {
        List<String> out = new ArrayList<>(1);
        out.add("voicecast client: " + status);
        return out;
    }
}
