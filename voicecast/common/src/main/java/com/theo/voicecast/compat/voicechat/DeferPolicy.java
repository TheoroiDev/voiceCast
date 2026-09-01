package com.theo.voicecast.compat.voicechat;

/**
 * Pure, testable decision for whether VoiceCast may open the microphone right
 * now given the configured Simple Voice Chat coexistence mode.
 *
 * <ul>
 *   <li>{@code share} (default): coexist — both mods may capture; the devices
 *       are usually shareable and VoiceCast releases its line on PTT release.</li>
 *   <li>{@code defer}: postpone opening while SVC captured audio recently
 *       (its PTT/voice-activity was active within the window); if the wait
 *       exceeds the timeout (e.g. SVC is in voice-activation mode and the
 *       user never stops talking) fall back to sharing instead of blocking
 *       forever.</li>
 * </ul>
 */
public final class DeferPolicy {
    /** SVC audio within this window counts as "currently transmitting". */
    public static final long SVC_DEFER_WINDOW_MS = 250L;
    /** Never defer the mic open longer than this; fall back to sharing. */
    public static final long SVC_DEFER_TIMEOUT_MS = 2000L;

    public enum Decision { OPEN, DEFER, FALLBACK_OPEN }

    private DeferPolicy() {}

    /**
     * @param deferMode        the configured {@code svcCoexistence = defer}
     * @param svcTransmitting  SVC captured audio within {@link #SVC_DEFER_WINDOW_MS}
     * @param deferredForMs    how long this PTT press has already been deferred (0 = not yet)
     */
    public static Decision decide(boolean deferMode, boolean svcTransmitting, long deferredForMs, long deferTimeoutMs) {
        if (!deferMode || !svcTransmitting) return Decision.OPEN;
        return deferredForMs >= deferTimeoutMs ? Decision.FALLBACK_OPEN : Decision.DEFER;
    }
}
