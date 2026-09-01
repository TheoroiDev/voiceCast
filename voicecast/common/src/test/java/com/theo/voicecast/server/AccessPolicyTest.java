package com.theo.voicecast.server;

import com.theo.voicecast.api.AccessCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest {

    private static final UUID P1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID P2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void masterSwitchOffDeniesEveryone() {
        AccessPolicy p = new AccessPolicy(false, Set.of(), null);
        assertFalse(p.allows(P1));
    }

    @Test
    void emptyWhitelistAllowsEveryone() {
        AccessPolicy p = new AccessPolicy(true, Set.of(), null);
        assertTrue(p.allows(P1));
        assertTrue(p.allows(P2));
    }

    @Test
    void nonEmptyWhitelistIsExclusive() {
        AccessPolicy p = new AccessPolicy(true, List.of(P1), null);
        assertTrue(p.allows(P1));
        assertFalse(p.allows(P2));
    }

    @Test
    void hookOverridesWhitelist() {
        AccessCheck everyone = id -> true;
        AccessPolicy p = new AccessPolicy(true, List.of(P1), everyone);
        assertTrue(p.allows(P2), "hook decides even when the whitelist excludes the player");

        AccessCheck nobody = id -> false;
        AccessPolicy p2 = new AccessPolicy(true, Set.of(), nobody);
        assertFalse(p2.allows(P1), "hook can deny players the whitelist would allow");
    }

    @Test
    void masterSwitchBeatsEverything() {
        AccessPolicy p = new AccessPolicy(false, Set.of(), id -> true);
        assertFalse(p.allows(P1), "enabled=false must win even over a permissive hook");
    }
}
