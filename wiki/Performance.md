# [English](Performance) | [中文](Performance-zh)

# Performance & Capacity

> [← Home](Home) · Previous: [Access Control](Access-Control)

Full math in the project's internal capacity analysis — this page is the cheat sheet.

## Bottleneck profile

| Resource | Magnitude | Notes |
|---|---|---|
| Network | ~3 KB/s per speaking player (Opus 24 kbps) | Over the vanilla connection — **never the bottleneck**; capped at 9 KB/s by `maxFramesPerSecond=15` |
| Memory (shared layer) | Vosk English 150–250 MB; all 4 languages 0.8–1 GB; IPA 400–600 MB | Each model loads **once per server** |
| Memory (sessions) | **30–80 MB per speaking player** (Kaldi Recognizer — the biggest item) | 100 Vosk players ⇒ 3–8 GB of session layer |
| CPU | Vosk streaming decode RTF ≈ 0.1–0.3 per stream | One speaking player ≈ 0.1–0.3 cores |
| CPU (IPA) | 0.5–2 s per utterance on a shared decode pool fixed at `min(4, cores-1)` threads | **Structural bottleneck**: queues under load |

## Capacity cheat sheet

| Server | Online | Simultaneously speaking |
|---|---|---|
| 4 cores / 8 GB | ~10–20 | 3–5 |
| 16 cores / 16 GB | ~50 | ~10 (better with a larger IPA pool) |
| 32 cores / 32 GB | ~100 | 20–30 (needs the scale-up items below first) |

**The current build works out of the box for ≤20 players.** 100-player servers need:

1. Idle recognizers closed after 30–60 s and rebuilt on demand (grammar rebuild is ms-level) — removes the 3–8 GB session-memory floor;
2. Configurable / degradable IPA decode pool.

## Load-reducing knobs (available today)

- `[server] maxFramesPerSecond`: lower it to throttle abusive clients (slightly choppier audio);
- Remove `ipa-phonemes` from `[engines] allowed`: disables the most expensive inference path, keeping only Vosk;
- `[server] enabled = false`: shut it all down (zero load);
- Single-language deployment: keep players on engines whose models you actually want loaded — the shared layer only loads what's used.

## Monitoring & diagnostics

- After startup, confirm `Server voice engine ready: <engine>` in the log;
- `-Dvoicecast.verbose=true` logs per-frame/per-utterance pipeline details (troubleshooting only — keep off in production);
- Session queues are bounded (32 frames, discard-oldest when full): overload manifests as "occasionally dropped sentences", never as lag spikes or crashes.
