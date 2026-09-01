package com.theo.voicecast.api.event;

import com.theo.voicecast.api.RecognitionResult;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired on the server when recognition finishes for a speaking player.
 * Posted from a worker thread; listeners that touch game state must marshal to
 * the server thread.
 */
public record ServerRecognitionFinalEvent(ServerPlayer player, RecognitionResult result) {}
