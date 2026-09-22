package com.theo.voicecast.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 F-B2 session build backoff: a failing recognizer build (native load of a
 * broken model dir) must engage ≥30 s of quiet, double per consecutive
 * failure up to the 5 min cap, and reset on success / explicit force.
 */
class BuildBackoffTest {

    @Test
    void firstFailureGatesForAtLeastBase() {
        BuildBackoff b = new BuildBackoff();
        long now = 1_000_000L;
        b.onFailure(now);
        assertFalse(b.allowed(now));
        assertFalse(b.allowed(now + BuildBackoff.BASE_MS - 1));
        assertTrue(b.allowed(now + BuildBackoff.BASE_MS), "BASE_MS is the '≥30s' floor (R2 F-B2)");
        assertEquals(1, b.consecutiveFailures());
    }

    @Test
    void consecutiveFailuresDoubleUpToTheCap() {
        BuildBackoff b = new BuildBackoff();
        long now = 0;
        b.onFailure(now);                    // 30 s
        assertEquals(now + BuildBackoff.BASE_MS, b.nextAttemptMs());
        now = b.nextAttemptMs();
        b.onFailure(now);                    // 60 s
        assertEquals(now + 2 * BuildBackoff.BASE_MS, b.nextAttemptMs());
        now = b.nextAttemptMs();
        b.onFailure(now);                    // 120 s
        assertEquals(now + 4 * BuildBackoff.BASE_MS, b.nextAttemptMs());
        for (int i = 0; i < 10; i++) {       // saturate
            now = b.nextAttemptMs();
            b.onFailure(now);
        }
        assertEquals(BuildBackoff.MAX_MS, b.nextAttemptMs() - now, "backoff capped at 5 min");
    }

    @Test
    void forceAndSuccessReset() {
        BuildBackoff b = new BuildBackoff();
        b.onFailure(0);
        assertFalse(b.allowed(0));
        b.force();                            // explicit engine re-selection
        assertTrue(b.allowed(0));
        b.onFailure(0);
        b.onSuccess();
        assertTrue(b.allowed(0), "a successful build clears the backoff");
        assertEquals(0, b.consecutiveFailures());
    }
}
