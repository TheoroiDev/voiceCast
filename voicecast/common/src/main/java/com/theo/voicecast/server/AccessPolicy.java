package com.theo.voicecast.server;

import com.theo.voicecast.api.AccessCheck;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, MC-free decision logic for who may stream audio to the server:
 * <ol>
 *   <li>the {@code [server] enabled} master switch must be on;</li>
 *   <li>an installed {@link AccessCheck} hook (permission mod bridge) wins;</li>
 *   <li>otherwise: an empty {@code [players] whitelist} allows everyone, a
 *       non-empty one allows only the listed UUIDs.</li>
 * </ol>
 * Unit-testable by design; {@link VoiceCastServer} supplies the config values.
 */
public final class AccessPolicy {
    private final boolean enabled;
    private final Set<UUID> whitelist;
    private final AccessCheck hook;

    public AccessPolicy(boolean enabled, Collection<UUID> whitelist, AccessCheck hook) {
        this.enabled = enabled;
        this.whitelist = whitelist == null ? Set.of() : Set.copyOf(whitelist);
        this.hook = hook;
    }

    public boolean allows(UUID playerId) {
        if (!enabled) return false;
        if (hook != null) return hook.allows(playerId);
        return whitelist.isEmpty() || whitelist.contains(playerId);
    }
}
