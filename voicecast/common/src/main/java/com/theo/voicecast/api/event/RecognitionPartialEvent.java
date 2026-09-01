package com.theo.voicecast.api.event;

import com.theo.voicecast.api.RecognitionResult;

/** Fired on the client for interim recognition results (HUD subtitles, debug). */
public record RecognitionPartialEvent(RecognitionResult result) {}
