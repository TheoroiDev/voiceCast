# [English](Performance) | [中文](Performance-zh)

# Performance & Capacity

> [← Home](Home) · Previous: [Access Control](Access-Control)

Full math in the project's internal capacity analysis — this page is the cheat sheet.

> **Baseline: 0.5.0 engine swap (Qwen3-ASR + ZIPA).** File sizes are read off the shipped
> models; RAM/RTF figures come from the engine-swap lab bench (L1: n=1660 clips; L2b: hotworded
> Qwen3 runs) until a production measured pass lands.

## Bottleneck profile

| Resource | Magnitude | Notes |
|---|---|---|
| Network | ~3 KB/s per speaking player (Opus 24 kbps) | Over the vanilla connection — **never the bottleneck**; capped at 9 KB/s by `maxFramesPerSecond=15` |
| Memory (Qwen3-ASR) | **~1.5–2.8 GB once per hotword set** (lab peak RSS, 8 threads), **at most 2 sets resident** | `qwen3-asr-0.6b-int8`: shared native recognizers are cached per (model dir, hotword set) across all sessions and LRU-bounded at 2 hotword sets (evicted sets are closed; same-set reuse never reloads); sessions are thin. The empty-transcript fallback adds one pinned shared hotword-free instance that never evicts |
| Memory (IPA / ZIPA) | ~0.5 GB **once per server** (lab process peak incl. runtime) | `zipa-ipa`: 70 MB int8 weights in a process-wide shared ORT session; per-player sessions are thin |
| CPU (Qwen3-ASR) | lab RTF median 0.165–0.170 hotworded @ 8 threads (single-row max spikes recorded) | decode is serialized per shared instance — CPU scales with speaking time, not session count |
| CPU (IPA / ZIPA) | lab RTF 0.014 mean / p95 0.021 (n=1660 clips) | decoded on a shared pool fixed at `min(4, cores-1)` threads |

Disk: the shipped model set is ~1.0 GB (Qwen3-ASR ~950 MB extracted + ZIPA 70 MB + the 0.5 MB gtcrn denoiser). Run dirs **hardlink** the workspace copies, so seeded run dirs add no extra disk on the same volume.

## Capacity cheat sheet

| Server | Online | Simultaneously speaking |
|---|---|---|
| 4 cores / 8 GB | ~10–20 | 3–6 (Qwen3 needs ~2 GB of the 8) |
| 16 cores / 16 GB | ~50 | 8–12 |
| 32 cores / 32 GB | ~100 | 15–25 |

**The current build works out of the box for ≤20 players.** The binding constraint is the shared
Qwen3-ASR instance (~1.5–2 GB native RAM per hotword set, outside the JVM heap) and its serialized
decode. Large servers should:

1. Keep the routed vocabulary (and with it the hotword set, capped at 100/session) stable — fewer distinct sets means fewer shared Qwen3 instances;
2. Keep the ZIPA decode pool bounded (it already is: `min(4, cores-1)` threads);
3. Re-evaluate with a production measured pass (the 0.6B int8 model at 8 threads has recorded single-row RTF max spikes).

## Load-reducing knobs (available today)

- `[server] maxFramesPerSecond`: lower it to throttle abusive clients (slightly choppier audio);
- `[engines] allowed`: engines are shared server-side, so the lever is **which** engines may load at all (e.g. dropping `zipa-ipa` removes a ~0.5 GB resident session) — keeping the routed vocabulary (and with it the hotword set, capped at 100/session) stable holds the Qwen3 layer to one shared instance;
- `[server] enabled = false`: shut it all down (zero load).

## Monitoring & diagnostics

- Shared layers log once at first use: `Server voice engine ready: <engine>` / `Shared ZIPA engine ready` / `Loading shared Qwen3-ASR model from … (hotwords=N)`;
- Recognizers log at session build: `sherpa Qwen3-ASR recognizer ready (engine=…, hotwords=N)` / `ZIPA phoneme recognizer ready` — the active hotword sets are visible there;
- `-Dvoicecast.verbose=true` logs per-frame/per-utterance pipeline details (troubleshooting only — keep off in production);
- Session queues are bounded (32 frames; when full, **newly arriving frames are dropped** — the older frame sequence is preserved): overload manifests as "occasionally dropped sentences", never as lag spikes or crashes.
