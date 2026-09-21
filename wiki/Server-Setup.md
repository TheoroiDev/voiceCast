# [English](Server-Setup) | [中文](Server-Setup-zh)

# Server Setup

> [← Home](Home) · Previous: [Getting Started](Getting-Started) · Next: [Configuration](Configuration)

## How it works (read this first)

Recognition runs **entirely on the server**:

- The client only captures the mic → Opus-compresses (about **3 KB/s** per speaking player) → sends it over the **vanilla Minecraft connection**;
- The server runs ONNX inference (Qwen3-ASR utterance / ZIPA phoneme models), matches the spell and casts;
- **No extra ports**, no extra firewall rules; players never download models or run inference.

## Install

Drop `voicecast-<loader>-*.jar` (or the fabric build) into the `mods/` folder. Required on both Client and Server.

## Model download

- The server **pre-warms the default engine** at start (`[server] defaultEngine`, the Qwen3-ASR catalog default, ~880 MB); other engines download on first selection by player and are **shared server-wide**;
- Downloads go over HTTPS with checksum/size verification. The Qwen3-ASR model comes from the official **sherpa-onnx GitHub release**; the **IPA (ZIPA)** model uses HuggingFace with the hf-mirror.com mirror — you can add extra mirrors in `models.json` (multiple `urls` are probed and downloaded fastest-first);
- **No internet / slow network**:
  - Proxy via JVM flags: `-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>` (the downloader also detects the `HTTPS_PROXY` env var);
  - Or set `[server] autoDownload = false` and **place models manually** into `config/voicecast/models/<modelId>/` (sherpa archives need the `.onnx` files at the model root; the ZIPA dir needs `model.int8.onnx` + `tokens.txt`);
- Model catalog and checksums: [Configuration](Configuration).

## Memory & hardware

| Scale | Recommendation |
|---|---|
| ≤20 online (3–5 speaking) | 4 cores / 8 GB |
| ~50 online (~10 speaking) | 16 cores / 16 GB |
| 100 online | 32 cores / 32 GB, plus idle-recognizer recycling (see [Performance](Performance)) |

Shared model layer: Qwen3-ASR ~1.5–2.8 GB resident (one shared instance per hotword set, lab-measured peak RSS); ZIPA ~0.5 GB incl. the ONNX runtime. Each speaking player adds 30–80 MB of session memory. Details in [Performance](Performance).

## Verify

1. The log shows `Server voice engine ready: <engine>` (`qwen3-asr-0.6b-int8` by default; other engines load lazily when a player selects them);
2. A client with a gameplay mod attached streams audio and the server logs recognition activity;
3. `/voicecast engine` shows the player's current engine.

## Notes

- **Dedicated-server safe**: the voicecast server code never references client/LWJGL classes; all-platform natives for sherpa-onnx/ONNX are bundled (Linux x64/arm, macOS work).

> [← Home](Home) · Previous: [Getting Started](Getting-Started) · Next: [Configuration](Configuration)