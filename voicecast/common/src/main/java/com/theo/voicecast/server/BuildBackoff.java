package com.theo.voicecast.server;

/**
 * Consecutive-failure backoff for session recognizer builds (R2 F-B2).
 *
 * <p>A model directory that passes the file-level probe but fails the native
 * load (or a recognizer whose {@code start()} throws) used to retry at FRAME
 * rate: every audio frame re-entered {@code ensureReady} → {@code
 * buildRecognizer} → a tens-of-seconds native load attempt → fail → next
 * frame. The backoff makes the first failure cost ≥ {@link #BASE_MS} of
 * quiet, doubling per consecutive failure up to {@link #MAX_MS}, and resets
 * on success or on an explicit engine re-selection ({@code force}).
 *
 * <p>Thread-safety: every method is synchronized — the gate is read on the
 * session worker ({@code ensureReady}) and written from the same worker's
 * build path, but tests and future callers must not rely on that.
 */
final class BuildBackoff {
    /** First-failure quiet period (R2 F-B2: "≥30s 或一次性失败后禁用本会话重载"). */
    static final long BASE_MS = 30_000;
    /** Cap so a permanently broken engine still self-retries every 5 min. */
    static final long MAX_MS = 300_000;

    private int consecutiveFailures;
    private long nextAttemptMs;

    /** Whether a build attempt is allowed at {@code nowMs}. */
    synchronized boolean allowed(long nowMs) {
        return nowMs >= nextAttemptMs;
    }

    /** Record a build failure: engage/double the quiet period. */
    synchronized void onFailure(long nowMs) {
        consecutiveFailures++;
        long backoff = Math.min(MAX_MS, BASE_MS << Math.min(consecutiveFailures - 1, 20));
        nextAttemptMs = nowMs + backoff;
    }

    /** Record a success: back to immediate builds. */
    synchronized void onSuccess() {
        consecutiveFailures = 0;
        nextAttemptMs = 0;
    }

    /** Explicit user action (engine re-selection) overrides the quiet period. */
    synchronized void force() {
        nextAttemptMs = 0;
    }

    /** Test/observability: consecutive failures since the last success. */
    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** Test/observability: earliest ms at which a build is allowed again. */
    synchronized long nextAttemptMs() {
        return nextAttemptMs;
    }
}
