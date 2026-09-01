package com.theo.voicecast.fabric;

import com.theo.voicecast.client.EngineSelectScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration: adds a "Config" button on the Mod Menu list that opens
 * the VoiceCast recognizer-engine chooser. Only loaded when Mod Menu is
 * installed (declared via the {@code modmenu} entrypoint).
 */
public final class VoiceCastModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return EngineSelectScreen::new;
    }
}
