package com.theo.voicecast.compat.voicechat;

import com.theo.voicecast.VoiceCast;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophoneMuteEvent;

/**
 * Simple Voice Chat integration plugin (M7b). Registration mechanism is
 * loader-specific (SVC does not use ServiceLoader on 2.x):
 * <ul>
 *   <li>Fabric: {@code fabric.mod.json} entrypoint key {@code voicechat}</li>
 *   <li>Forge: {@link ForgeVoicechatPlugin} annotation scan (FML ModFileScanData)</li>
 * </ul>
 * The class is only instantiated by SVC when SVC is installed — never touches
 * SVC classes otherwise. This side is pure observation: we track SVC's
 * transmit state so {@code VoiceCastClient} can apply the configured
 * coexistence mode (share / defer). We never cancel or modify SVC audio.
 */
@ForgeVoicechatPlugin
public class VoiceCastSvcPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "voicecast";
    }

    @Override
    public void initialize(VoicechatApi api) {
        SvcState.markPresent();
        VoiceCast.LOGGER.info("Simple Voice Chat detected — voicecast coexistence integration active");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatConnectionEvent.class,
                e -> SvcState.setConnected(e.isConnected()));
        registration.registerEvent(ClientSoundEvent.class,
                e -> SvcState.onTransmit());
        registration.registerEvent(MicrophoneMuteEvent.class,
                e -> SvcState.setMuted(e.isDisabled()));
    }
}
