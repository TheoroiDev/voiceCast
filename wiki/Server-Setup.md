# [English](Server-Setup) | [中文](Server-Setup-zh)

# Server Setup

> [← Home](Home) · Next: [Configuration](Configuration)

## How it works (read this first)

Recognition runs **entirely on the server**:

- The client only captures the mic → Opus-compresses (about **3 KB/s** per speaking player) → sends it over the **vanilla Minecraft connection**;
- The server runs Vosk / ONNX inference, matches the spell and casts;
- **No extra ports**, no extra firewall rules; players never download models or run inference.

## Install

Drop `voicecast-forge-*.jar` (or the fabric build) into the server's `mods/`. Client and server both get the mod.

## Model download

- The server **pre-warms the default engine** at start (`[server] defaultEngine`, Vosk English ~40 MB by default); other engines download on first selection and are **shared server-wide**;
- Downloads go over HTTPS with sha256 verification. Vosk models come from **alphacephei.com** by default; the **IPA (wav2vec2)** model uses the hf-mirror.com mirror — you can add extra mirrors for Vosk models in `models.json` (multiple `urls` are probed and downloaded fastest-first);
- **No internet / slow link**:
  - Proxy via JVM flags: `-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>` (the downloader also detects the `HTTPS_PROXY` env var);
  - Or set `[server] autoDownload = false` and **place models manually** into `config/voicecast/models/<modelId>/` (extracted Vosk needs `am/ conf/ graph/` subdirectories);
- Model catalog and checksums: [Configuration](Configuration).

## Memory & hardware

| Scale | Recommendation |
|---|---|
| ≤20 online (3–5 speaking) | 4 cores / 8 GB |
| ~50 online (~10 speaking) | 16 cores / 16 GB |
| 100 online | 32 cores / 32 GB, plus idle-recognizer recycling (see [Performance](Performance)) |

Shared model layer: Vosk English ~150–250 MB; all four Vosk languages ~0.8–1 GB; IPA (q4 weights + ONNX runtime) ~400–600 MB. Each speaking player adds 30–80 MB of session memory. Details in [Performance](Performance).

## Verify

1. The log shows `Server voice engine ready: <engine>` (`vosk-en` by default; other languages load lazily when a player selects them);
2. A client with a gameplay mod attached streams audio and the server logs recognition activity;
3. `/voicecast engine` shows the player's current engine.

## Notes

- **Dedicated-server safe**: the voicecast server code never references client/LWJGL classes; all-platform natives for Vosk/ONNX are bundled (Linux x64/arm, macOS work);
- Upgrading: the config schema migrates automatically (versioned `config/voicecast/voicecast.toml`); legacy `server.properties`/`client.properties` are imported once and deleted.
