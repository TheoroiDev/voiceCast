package com.theo.voicecast.api.event;

/**
 * Lifecycle state reported by an active recognizer.
 */
public enum RecognizerState {
    LOADING,
    READY,
    LISTENING,
    NO_MODEL,
    MICROPHONE_UNAVAILABLE,
    ERROR
}
