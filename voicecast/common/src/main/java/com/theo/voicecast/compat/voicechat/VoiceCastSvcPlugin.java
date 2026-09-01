package com.theo.voicecast.compat.voicechat;

import com.theo.voicecast.VoiceCast;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophoneMuteEvent;

/**
 * Simple Voice Chat integration plugin (M7b). Loaded by SVC's ServiceLoader
 * only when SVC is installed — never touches SVC classes otherwise. This
 * side is pure observation: we track SVC's transmit state so
 * {@code VoiceCastClient} can apply the configured coexistence mode
 * (share / defer). We never cancel or modify SVC audio.
 */
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
