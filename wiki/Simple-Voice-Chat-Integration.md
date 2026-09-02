# [English](Simple-Voice-Chat-Integration) | [中文](Simple-Voice-Chat-Integration-zh)

# Simple Voice Chat Integration

> [← Home](Home) · Related: [Configuration](Configuration) (`[compat]` section)

VoiceCast ships a **first-class coexistence integration** with [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) (SVC). It is **optional**: with SVC absent nothing changes, with SVC installed the two mods share the microphone intelligently.

## How it works

- VoiceCast registers an SVC plugin (`VoicechatPlugin`, id `voicecast`) — on Fabric via the `voicechat` entrypoint, on Forge via the `@ForgeVoicechatPlugin` annotation scan. The plugin class is only instantiated when SVC is installed;
- The plugin **observes** SVC's client state (connection, microphone mute, mic packets) and feeds a lock-free snapshot;
- VoiceCast **never cancels or modifies** SVC audio, and SVC never touches VoiceCast's pipeline.

## Coexistence (share only)

VoiceCast **shares** the microphone with SVC: it opens its mic as usual; if SVC captured audio within the last 300 ms, one info line is logged. Devices are normally shareable (SVC uses OpenAL capture, VoiceCast uses Java Sound), and VoiceCast releases its line the moment push-to-talk ends.

## Failure resilience

If the microphone device is exclusively held (open fails), VoiceCast logs a clear message ("Simple Voice Chat is active and may hold the device") and retries **once after 500 ms** — transiently busy devices recover within moments. The next push-to-talk press retries again.

## Setup

Nothing to do — coexistence is automatic. `[compat] svcCoexistence` is a **client-local setting**: each player's own config on their own machine — the server neither reads nor syncs it.

## Known interaction (by design)

Your incantation is **audible to other players** through SVC while you chant — you are speaking out loud, after all. VoiceCast never mutes SVC on your behalf.
