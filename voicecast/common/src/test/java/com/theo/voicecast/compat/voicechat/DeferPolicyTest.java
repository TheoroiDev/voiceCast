package com.theo.voicecast.compat.voicechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeferPolicyTest {

    private static final long TIMEOUT = DeferPolicy.SVC_DEFER_TIMEOUT_MS;

    @Test
    void shareModeAlwaysOpens() {
        assertEquals(DeferPolicy.Decision.OPEN,
                DeferPolicy.decide(false, true, 0, TIMEOUT));
        assertEquals(DeferPolicy.Decision.OPEN,
                DeferPolicy.decide(false, true, TIMEOUT * 10, TIMEOUT));
    }

    @Test
    void deferModeOpensWhenSvcSilent() {
        assertEquals(DeferPolicy.Decision.OPEN,
                DeferPolicy.decide(true, false, 0, TIMEOUT));
    }

    @Test
    void deferModeDefersWhileSvcTransmits() {
        assertEquals(DeferPolicy.Decision.DEFER,
                DeferPolicy.decide(true, true, 0, TIMEOUT));
        assertEquals(DeferPolicy.Decision.DEFER,
                DeferPolicy.decide(true, true, TIMEOUT - 1, TIMEOUT));
    }

    @Test
    void deferModeFallsBackAfterTimeout() {
        assertEquals(DeferPolicy.Decision.FALLBACK_OPEN,
                DeferPolicy.decide(true, true, TIMEOUT, TIMEOUT));
        assertEquals(DeferPolicy.Decision.FALLBACK_OPEN,
                DeferPolicy.decide(true, true, TIMEOUT + 5000, TIMEOUT));
    }

    @Test
    void windowConstantIsSane() {
        // the transmit window must be shorter than the fallback timeout,
        // otherwise defer could never recover inside a press
        org.junit.jupiter.api.Assertions.assertTrue(DeferPolicy.SVC_DEFER_WINDOW_MS < DeferPolicy.SVC_DEFER_TIMEOUT_MS);
    }
}
