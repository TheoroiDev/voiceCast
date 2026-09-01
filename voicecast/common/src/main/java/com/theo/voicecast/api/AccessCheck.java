package com.theo.voicecast.api;

import java.util.UUID;

/**
 * Optional, pluggable access decision for the server-side recognizer
 * (installed via {@code VoiceCastServer.setAccessCheck}). When present it
 * overrides the {@code [players]} whitelist in voicecast.toml — a hook for
 * permission mods (e.g. LuckPerms bridges) to answer "may this player use
 * voice casting?".
 */
@FunctionalInterface
public interface AccessCheck {
    boolean allows(UUID playerId);
}
