# [English](Access-Control) | [中文](Access-Control-zh)

# Access Control

> [← Home](Home) · Previous: [Configuration](Configuration) · Next: [Performance](Performance)

## Decision order

Every player's audio frames / control packets / engine selection are checked at the server entry:

```
[server] enabled = false ?          → deny (server-wide off)
   ↓ true
AccessCheck hook installed?         → the hook decides (permission-mod bridge)
   ↓ no hook
[players] whitelist empty?          → empty = everyone allowed
   ↓ non-empty
player UUID listed?                 → yes = allow / no = deny
```

## What denied players see

- Audio frames are **silently dropped** (no server load);
- They receive **one localized notice per session** (client language):
  - server disabled → "VoiceCast is disabled on this server"
  - not whitelisted → "you are not allowed to use voice here"
- Engine selection is denied too — **no session is created** for unauthorized players, so they can't trigger any model downloads.

## Managing the whitelist

```toml
[players]
whitelist = [
  "11111111-1111-1111-1111-111111111111",
  "22222222-2222-2222-2222-222222222222"
]
```

- Get a UUID via `/data get entity <player> UUID` (server console) or the client debug screen;
- Malformed UUIDs are skipped with a log warning (other entries still apply);
- Restart the server after edits.

## Engine whitelist vs player whitelist

- `[engines] allowed` controls **which engine** anyone may use (prevents selecting the big IPA model);
- `[players] whitelist` controls **who** may use voice at all;
- Both apply independently: whitelisted players still can only pick engines from `[engines] allowed`.

## Permission-mod bridge (reserved)

After startup a plugin can install `com.theo.voicecast.api.AccessCheck` (a SAM interface) to override the UUID whitelist:

```java
VoiceCastServer.INSTANCE.setAccessCheck(playerId -> permissionMod.has(playerId, "voicecast.use"));
```

- The hook **overrides** `[players] whitelist`, but `[server] enabled=false` still wins;
- Designed for LuckPerms-style bridges over the Fabric permissions API / Forge permissions (no concrete bridge ships in this version).

## Disabling voice while keeping the gameplay

```toml
[server]
enabled = false
```

No models are loaded and no recognizers built; gameplay mods keep working (scroll-based casting in WizardReal is unaffected — there's just no voice trigger).
