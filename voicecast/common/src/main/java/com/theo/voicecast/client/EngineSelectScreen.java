package com.theo.voicecast.client;

import com.theo.voicecast.model.ModelConfig;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * Picker for which recognizer engine the server should run for you. Buttons
 * are generated from the models.json v2 catalog in declaration order (one
 * model = one engine), so server operators control what players can pick by
 * editing the catalog. Opened from Mod Menu's Config button (Fabric), the
 * mod-list config button (Forge), or {@code /voicecast settings}. Supports a
 * parent screen so closing returns to Mod Menu / the mods list.
 */
public final class EngineSelectScreen extends Screen {
    /** 6 model buttons fit the fixed layout; catalogs beyond that need scrolling (not built yet). */
    private static final int MAX_BUTTONS = 6;
    private final Screen parent;
    private List<String> modelIds = List.of();

    public EngineSelectScreen() {
        this(null);
    }

    public EngineSelectScreen(Screen parent) {
        super(Component.translatable("voicecast.engine.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Minecraft mc = this.minecraft;
        ModelConfig catalog = mc != null && mc.gameDirectory != null
                ? ModelConfig.load(mc.gameDirectory.toPath())
                : null;
        modelIds = catalog == null ? List.of() : catalog.modelIds();

        String current = EnginePicker.effectiveEngine();
        int w = 260, h = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 44;

        int shown = Math.min(modelIds.size(), MAX_BUTTONS);
        for (int i = 0; i < shown; i++) {
            String id = modelIds.get(i);
            addRenderableWidget(Button.builder(label(id, id.equals(current)),
                    b -> pick(id)).bounds(x, y + i * 24, w, h).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                b -> this.onClose()).bounds(x, y + Math.max(shown, 1) * 24, w, h).build());
    }

    /** Catalog lang key with graceful fallback to the raw model id. */
    private Component label(String modelId, boolean active) {
        String key = "voicecast.engine." + modelId.replace('-', '_');
        String text = Language.getInstance().getOrDefault(key, modelId);
        return Component.literal(text).withStyle(active ? ChatFormatting.BOLD : ChatFormatting.RESET);
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
