package com.theo.voicecast.api.event;

/** Microphone RMS level in 0..1, for HUD meters. */
public record AudioLevelEvent(float level) {}
