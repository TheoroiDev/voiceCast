package com.theo.voicecast.client.hud;

import com.theo.voicecast.api.VoiceCastEvents;
import com.theo.voicecast.api.event.AudioLevelEvent;
import com.theo.voicecast.api.event.RecognitionFinalEvent;
import com.theo.voicecast.api.event.RecognitionPartialEvent;
import com.theo.voicecast.api.event.RecognizerState;
import com.theo.voicecast.api.event.RecognizerStateEvent;
import com.theo.voicecast.config.VoiceCastConfig;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * VoiceCast HUD: a waveform plus the recognized words, rendered above the
 * crosshair only while an external mod (WizardReal) has enabled voice casting.
 * A localized status line (model download, errors) shows while the recognizer
 * is not ready.
 * <ul>
 *     <li>Red noise = model still loading/downloading (or no model).</li>
 *     <li>Green waveform = ready; audio-reactive while PTT is held.</li>
 *     <li>Gray italic partial result while listening; white final result that
 *     fades out ({@link VoiceCastConfig#transcriptFadeMs}).</li>
 * </ul>
 */
public final class VoiceCastHud {
    public static final VoiceCastHud INSTANCE = new VoiceCastHud();

    private static final int BAR_COUNT = 16;
    private static final int BAR_WIDTH = 3;
    private static final int BAR_GAP = 1;
    private static final int MAX_BAR_HEIGHT = 22;
    /** Crosshair-relative Y of the waveform bar center. */
    private static final int WAVE_Y_OFFSET = 32;
    /** Crosshair-relative Y of the recognized-text line. */
    private static final int TEXT_Y_OFFSET = 18;
    private static final long FADE_MS = 250L;

    private final Random random = new Random();
    private final float[] barHeights = new float[BAR_COUNT];

    private volatile boolean enabled;
    private volatile boolean pttHeld;
    private volatile float audioLevel;
    private volatile RecognizerState state = RecognizerState.READY;
    private volatile String stateKey = "";
    private volatile java.util.List<String> stateArgs = java.util.List.of();
    private volatile long lastEnabledChangeMs;

    private volatile String partialText = "";
    private volatile String finalText = "";
    private volatile long finalAtMs;

    private VoiceCastHud() {
        VoiceCastEvents.subscribe(AudioLevelEvent.class, e -> audioLevel = e.level());
        VoiceCastEvents.subscribe(RecognizerStateEvent.class, e -> {
            state = e.state();
            stateKey = e.key();
            stateArgs = e.args();
        });
        VoiceCastEvents.subscribe(RecognitionPartialEvent.class, e -> {
            if (e.result() == null) return;
            String t = e.result().text();
            partialText = t == null ? "" : t.trim();
        });
        VoiceCastEvents.subscribe(RecognitionFinalEvent.class, e -> {
            if (e.result() == null) return;
            String t = e.result().text();
            finalText = t == null ? "" : t.trim();
            finalAtMs = System.currentTimeMillis();
            partialText = "";
        });
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            this.lastEnabledChangeMs = System.currentTimeMillis();
        }
        if (!enabled) {
            pttHeld = false;
        }
    }

    public void setPttHeld(boolean held) {
        this.pttHeld = held;
    }

    public void render(GuiGraphics context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (!enabled) {
            if (now - lastEnabledChangeMs > FADE_MS) return;
        }
        float alpha = enabled ? 1f : Math.max(0f, 1f - (now - lastEnabledChangeMs) / (float) FADE_MS);
        int alphaByte = Math.round(0xFF * alpha);

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int centerX = sw / 2;

        renderStatus(context, mc, sh, centerX, alphaByte);
        renderWaveform(context, now, sh, centerX, alphaByte);
        renderTranscript(context, mc, now, sh, centerX, alphaByte);
    }

    /**
     * Localized server state line (shown above the waveform while the
     * recognizer is not ready): model downloads, load failures, mic errors.
     */
    private void renderStatus(GuiGraphics context, Minecraft mc, int sh, int centerX, int alphaByte) {
        if (stateKey.isEmpty()) return;
        boolean terminalError = state == RecognizerState.NO_MODEL
                || state == RecognizerState.ERROR
                || state == RecognizerState.MICROPHONE_UNAVAILABLE;
        // Only show while it matters (loading/errors); READY/LISTENING are
        // conveyed by the waveform itself.
        if (!terminalError && state != RecognizerState.LOADING) return;

        MutableComponent line = Component.translatable(stateKey, stateArgs.toArray())
                .withStyle(terminalError ? ChatFormatting.RED : ChatFormatting.GOLD);
        int argb = (alphaByte << 24) | 0x00FFFFFF;
        int x = centerX - mc.font.width(line) / 2;
        context.drawString(mc.font, line, x, sh / 2 - WAVE_Y_OFFSET - 14, argb);
    }

    private void renderWaveform(GuiGraphics context, long now, int sh, int centerX, int alphaByte) {
        boolean noisy = state == RecognizerState.LOADING || state == RecognizerState.NO_MODEL;

        int baseY = sh / 2 - WAVE_Y_OFFSET;
        int totalWidth = BAR_COUNT * (BAR_WIDTH + BAR_GAP) - BAR_GAP;
        int startX = centerX - totalWidth / 2;

        int color = noisy ? 0xFFFF3333 : 0xFF33FF33;
        int argb = (alphaByte << 24) | (color & 0x00FFFFFF);

        updateWaveform(noisy, now);

        for (int i = 0; i < BAR_COUNT; i++) {
            int h = Math.round(barHeights[i]);
            int x = startX + i * (BAR_WIDTH + BAR_GAP);
            int y1 = baseY - h / 2;
            int y2 = baseY + h / 2;
            fill(context, x, y1, x + BAR_WIDTH, y2, argb);
        }
    }

    /** The recognized words: live partial while listening, fading final otherwise. */
    private void renderTranscript(GuiGraphics context, Minecraft mc, long now,
                                  int sh, int centerX, int alphaByte) {
        if (!VoiceCastConfig.INSTANCE.transcriptHud) return;

        boolean hasPartial = pttHeld && !partialText.isBlank();
        long age = now - finalAtMs;
        boolean hasFinal = !finalText.isBlank() && age < VoiceCastConfig.INSTANCE.transcriptFadeMs;
        if (!hasPartial && !hasFinal) return;

        Font tr = mc.font;
        MutableComponent line;
        if (hasPartial) {
            line = Component.literal(partialText).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        } else {
            float fade = 1f - Math.min(1f, (float) age / VoiceCastConfig.INSTANCE.transcriptFadeMs);
            line = Component.literal("“" + finalText + "”");
            // Re-color via style alpha: multiply the white text with the fade.
            line = line.withStyle(ChatFormatting.WHITE);
            alphaByte = Math.round(0xFF * fade);
        }
        int argb = (alphaByte << 24) | 0x00FFFFFF;
        int x = centerX - tr.width(line) / 2;
        int y = sh / 2 - TEXT_Y_OFFSET;
        context.drawString(tr, line, x, y, argb);
    }

    private void updateWaveform(boolean noisy, long now) {
        for (int i = 0; i < BAR_COUNT; i++) {
            float target;
            if (noisy) {
                // Red static/noise: random jagged heights.
                target = 2 + random.nextFloat() * (MAX_BAR_HEIGHT - 2);
            } else if (pttHeld) {
                // Green waveform: audio level + a travelling sine.
                float wave = (float) Math.sin((now / 120.0) + i * 0.6);
                float level = Math.min(1f, audioLevel * 4f);
                target = 4 + (wave * 0.5f + 0.5f + level) * (MAX_BAR_HEIGHT - 4) * 0.5f;
            } else {
                // Ready but not holding PTT: calm idle line.
                float wave = (float) Math.sin((now / 250.0) + i * 0.4);
                target = 4 + wave * 2f;
            }
            // Smooth transitions between frames.
            barHeights[i] += (target - barHeights[i]) * 0.35f;
        }
    }

    private static void fill(GuiGraphics ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.fill(x1, y1, x2, y2, color);
    }
}
