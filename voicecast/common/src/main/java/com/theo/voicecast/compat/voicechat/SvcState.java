package com.theo.voicecast.compat.voicechat;

/**
 * SVC-class-free snapshot of the Simple Voice Chat client state, fed by
 * {@link VoiceCastSvcPlugin} (which is only loaded when SVC is installed).
 * Everything here is written from SVC's audio/network threads and read from
 * the client thread — all fields are volatile, no locks, no game-thread
 * access on the write side.
 */
public final class SvcState {
    private static volatile boolean present;      // plugin instance exists = SVC installed
    private static volatile boolean connected;    // SVC voice connection established
    private static volatile boolean muted;        // SVC microphone disabled
    private static volatile long lastTransmitMs;  // last SVC mic packet (0 = never)

    private SvcState() {}

    static void markPresent() {
        present = true;
    }

    static void setConnected(boolean c) {
        connected = c;
        if (!c) lastTransmitMs = 0; // a stale transmit timestamp must not survive a disconnect
    }

    static void setMuted(boolean m) {
        muted = m;
    }

    /** Called for every SVC-captured mic packet (SVC audio thread). */
    static void onTransmit() {
        lastTransmitMs = System.currentTimeMillis();
    }

    /** True when the SVC plugin has been loaded (i.e. Simple Voice Chat is installed). */
    public static boolean isPresent() {
        return present;
    }

    public static boolean isConnected() {
        return connected;
    }

    public static boolean isMuted() {
        return muted;
    }

    /** True when SVC captured microphone audio within the last {@code windowMs}. */
    public static boolean transmittingWithin(long windowMs) {
        long t = lastTransmitMs;
        return t > 0 && System.currentTimeMillis() - t < windowMs;
    }
}
