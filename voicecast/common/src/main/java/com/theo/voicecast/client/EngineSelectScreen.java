package com.theo.voicecast.client;

import com.theo.voicecast.config.ClientVoiceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Picker for which recognizer engine the server should run for you (a Vosk word
 * model per language — en ~40 MB, zh ~44 MB, ja ~50 MB, ko ~87 MB — or IPA
 * phonemes ~230 MB). Opened from Mod Menu's Config button (Fabric), the
 * mod-list config button (Forge), or {@code /voicecast settings}. Supports a
 * parent screen so closing returns to Mod Menu / the mods list.
 */
public final class EngineSelectScreen extends Screen {
    private final Screen parent;

    public EngineSelectScreen() {
        this(null);
    }

    public EngineSelectScreen(Screen parent) {
        super(Component.translatable("voicecast.engine.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        String current = EnginePicker.preferred();
        int w = 260, h = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 44;

        addRenderableWidget(Button.builder(label("voicecast.engine.vosk", current.equals(ClientVoiceConfig.ENGINE_VOSK)),
                b -> pick(ClientVoiceConfig.ENGINE_VOSK)).bounds(x, y, w, h).build());
        addRenderableWidget(Button.builder(label("voicecast.engine.vosk_cn", current.equals(ClientVoiceConfig.ENGINE_VOSK_CN)),
                b -> pick(ClientVoiceConfig.ENGINE_VOSK_CN)).bounds(x, y + 24, w, h).build());
        addRenderableWidget(Button.builder(label("voicecast.engine.vosk_jp", current.equals(ClientVoiceConfig.ENGINE_VOSK_JP)),
                b -> pick(ClientVoiceConfig.ENGINE_VOSK_JP)).bounds(x, y + 48, w, h).build());
        addRenderableWidget(Button.builder(label("voicecast.engine.vosk_kr", current.equals(ClientVoiceConfig.ENGINE_VOSK_KR)),
                b -> pick(ClientVoiceConfig.ENGINE_VOSK_KR)).bounds(x, y + 72, w, h).build());
        addRenderableWidget(Button.builder(label("voicecast.engine.ipa", current.equals(ClientVoiceConfig.ENGINE_IPA)),
                b -> pick(ClientVoiceConfig.ENGINE_IPA)).bounds(x, y + 96, w, h).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                b -> this.onClose()).bounds(x, y + 124, w, h).build());
    }

    private Component label(String key, boolean active) {
        return Component.translatable(key).withStyle(active ? ChatFormatting.BOLD : ChatFormatting.RESET);
    }

    private void pick(String engine) {
        EnginePicker.request(engine);
        this.rebuildWidgets(); // refresh the "active" styling
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        int cx = this.width / 2;
        int y = this.height / 2 - 76;
        ctx.drawCenteredString(this.font,
                Component.translatable("voicecast.engine.title").withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD),
                cx, y, 0xFFFFFF);
        ctx.drawCenteredString(this.font,
                Component.translatable("voicecast.engine.subtitle"), cx, y + 14, 0xC0C0C0);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
