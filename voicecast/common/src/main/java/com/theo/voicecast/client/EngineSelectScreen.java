package com.theo.voicecast.client;

import com.theo.voicecast.config.ClientVoiceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Picker for which recognizer engine the server should run for you (Vosk words
 * ~40 MB, or IPA phonemes ~230 MB). Opened from Mod Menu's Config button
 * (Fabric), the mod-list config button (Forge), or {@code /voicecast settings}.
 * Supports a parent screen so closing returns to Mod Menu / the mods list.
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
        int y = this.height / 2 - 30;

        Button vosk = Button.builder(label("voicecast.engine.vosk", current.equals(ClientVoiceConfig.ENGINE_VOSK)),
                b -> pick(ClientVoiceConfig.ENGINE_VOSK)).bounds(x, y, w, h).build();
        Button ipa = Button.builder(label("voicecast.engine.ipa", current.equals(ClientVoiceConfig.ENGINE_IPA)),
                b -> pick(ClientVoiceConfig.ENGINE_IPA)).bounds(x, y + 26, w, h).build();
        Button done = Button.builder(Component.translatable("gui.done"),
                b -> this.onClose()).bounds(x, y + 64, w, h).build();

        addRenderableWidget(vosk);
        addRenderableWidget(ipa);
        addRenderableWidget(done);
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
        int y = this.height / 2 - 70;
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
