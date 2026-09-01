package com.theo.voicecast.api.event;

import com.theo.voicecast.api.RecognitionResult;

/** Fired on the client when an utterance is recognized. */
public record RecognitionFinalEvent(RecognitionResult result) {}
