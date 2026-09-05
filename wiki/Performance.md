# [English](Performance) | [中文](Performance-zh)

# Performance & Capacity

> [← Home](Home) · Previous: [Access Control](Access-Control)

Full math in the project's internal capacity analysis — this page is the cheat sheet.

> **Baseline: 0.4.0 (sherpa-onnx engines; vosk removed).** File sizes are read off the shipped
> models; RAM/CPU figures are engineering estimates (weights + ONNX Runtime overhead + the
> acceptance gates in the implementation design) until a measured pass lands.

## Bottleneck profile

| Resource | Magnitude | Notes |
|---|---|---|
| Network | ~3 KB/s per speaking player (Opus 24 kbps) | Over the vanilla connection — **never the bottleneck**; capped at 9 KB/s by `maxFramesPerSecond=15` |
| Memory (streaming engine) | **~250–400 MB per speaking player** (est.) — no shared layer today | `sherpa-zh-en` loads int8 weights per session: encoder 182 + decoder 13 + joiner 3 MB ≈ 199 MB, plus ONNX Runtime overhead; 2 decode threads. The model dir also ships fp32 variants that are never loaded |
| Memory (IPA) | 400–600 MB **once per server** | `ipa-phonemes`: 230 MB q4 weights in a process-wide shared ONNX session; per-player sessions are thin |
| Memory (SenseVoice, planned) | target ≤ 400 MB shared (cross-source estimates 365–650 MB) | Offline whole-utterance engine (zh/en/ja/ko): shared singleton + serial decode; model not bundled yet |
| CPU (streaming) | RTF ≤ 0.15 per stream @ 2 threads (acceptance gate) | ≈ 0.15–0.3 cores per speaking player while speaking |
| CPU (IPA) | 0.5–2 s per utterance on a shared decode pool fixed at `min(4, cores-1)` threads | **Structural bottleneck**: queues under load |

Disk: the shipped model set is ~430 MB (~199 MB of int8 streaming weights actually read at runtime, plus the 242 MB IPA q4 model). Run dirs **hardlink** the workspace copies, so seeded run dirs add no extra disk on the same volume.

## Capacity cheat sheet

| Server | Online | Simultaneously speaking (streaming engine) |
|---|---|---|
| 4 cores / 8 GB | ~10–20 | 3–6 |
| 16 cores / 16 GB | ~50 | 8–12 |
| 32 cores / 32 GB | ~100 | 15–25 |

**The current build works out of the box for ≤20 players.** The binding constraint today is the
per-session model load (~250–400 MB native RAM each, outside the JVM heap). 100-player servers need:

1. The per-engine shared `OnlineRecognizer` from the implementation design (§3.3: one shared recognizer, one `OnlineStream` per session) — collapses the session layer to per-stream state only;
2. Idle recognizers closed after 30–60 s and rebuilt on demand;
3. Configurable / degradable IPA decode pool.

## Load-reducing knobs (available today)

- `[server] maxFramesPerSecond`: lower it to throttle abusive clients (slightly choppier audio);
- `[engines] allowed`: every allowed streaming engine costs a full model load **per speaking session** — trimming `allowed` (e.g. removing `ipa-phonemes`, the most expensive inference path) is the strongest lever. The streaming model already covers zh+en in one load, so there is no per-language memory saving to be had;
- `[server] enabled = false`: shut it all down (zero load).

## Monitoring & diagnostics

- Shared layers log once at first use: `Server voice engine ready: <engine>` / `Shared IPA engine ready`;
- Per-session engines log per player: `sherpa streaming recognizer ready (engine=…, hotwords=…)` — one such line per speaking player, each standing for one model load;
- `-Dvoicecast.verbose=true` logs per-frame/per-utterance pipeline details (troubleshooting only — keep off in production);
- Session queues are bounded (32 frames; when full, **newly arriving frames are dropped** — the older frame sequence is preserved): overload manifests as "occasionally dropped sentences", never as lag spikes or crashes.
