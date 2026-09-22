# [English](Configuration) | [中文](Configuration-zh)

# Configuration Reference

> [← Home](Home) · Previous: [Server Setup](Server-Setup) · Next: [Access Control](Access-Control)

VoiceCast uses **one shared config file** (client/server read their own sections):

- `<gameDir>/config/voicecast/voicecast.toml` — switches, engines, whitelist
- `<gameDir>/config/voicecast/models.json` — model catalog (v2) & mirrors
- Model files: `config/voicecast/models/<modelName>/`

Both files are auto-created on first load and rewritten (versioned; missing keys get defaults). Restart the server after edits.

## voicecast.toml

```toml
version = 1

[server]
defaultEngine = ""            # model name or language code; empty = catalog default (first declared zh model)
autoDownload = true           # allow server-side model downloads
maxFramesPerSecond = 15       # per-session audio frame cap (abuse guard)
enabled = true                # master switch: false = nobody may stream

[engines]
allowed = []                  # engine ids; empty = every catalog model is allowed

[players]
whitelist = []                # array of UUID strings; empty = everyone

[compat]
svcCoexistence = "share"      # Simple Voice Chat coexistence (client-local)

[client]                      # ← player-local section
engine = ""                   # model name / language code; empty = catalog default
noiseSuppression = false      # recognition-path mic denoising (GTCRN); see note below
```

| Key | Meaning |
|---|---|
| `[server] defaultEngine` | Engine pre-warmed at startup. Accepts a model name or a two-letter language code; empty/unknown = catalog default (first declared model supporting zh) |
| `[server] autoDownload` | `false` disables all downloads; a missing model reports `NO_MODEL` (requires placing files manually) |
| `[server] maxFramesPerSecond` | Max audio frames per player per second; excess frames are dropped (anti-spam) |
| `[server] enabled` | **Master switch**. `false`: no model warm-up, all audio frames silently dropped, players get a one-time "disabled" notice |
| `[engines] allowed` | Whitelist of selectable engines. **Empty = every catalog model is allowed**; non-empty = exactly those ids |
| `[players] whitelist` | UUID array (invalid UUIDs are skipped with a warning). **Empty = everyone**; non-empty = only listed players may stream. Order of checks: [Access Control](Access-Control) |
| `[compat] svcCoexistence` | Simple Voice Chat coexistence mode (**client-local**) — see [Simple Voice Chat Integration](Simple-Voice-Chat-Integration) |
| `[client] engine` | Player-local engine preference: a model name, a language code (`en`/`zh`/`ja`/`ko`...), or empty for the catalog default. Adjustable via `/voicecast engine <arg>` and `/voicecast settings` |
| `[client] noiseSuppression` | Microphone noise suppression (GTCRN via sherpa-onnx) for the **recognition path only** — default off. What other players hear through Simple Voice Chat is a separate capture: use SVC's own noise suppression for that channel |

> Engine ids are **model names** from `models.json` (one model = one engine). Default catalog: `qwen3-asr-0.6b-int8` (offline utterance, en/zh/ja/ko/yue/de/fr/es/ru, default), `zipa-ipa` (IPA phonemes), `gtcrn-simple-denoiser` (auxiliary denoiser). Each model downloads once, server-side, and is shared by every session using it.

## models.json (model catalog, v2)

Auto-created with defaults and **fully user-owned** (saved back as-parsed; there are no migrations — a file not in v2 shape is rewritten with defaults). One model = one engine: the model name is the engine id and the model directory.

```json
{
  "version": 2,
  "$schema": "docs/schemas/voicecast-models-v2.schema.json",
  "models": {
    "qwen3-asr-0.6b-int8": {
      "properties": {
        "lang": ["en","zh","ja","ko","yue","de","fr","es","ru"], "type": "offline",
        "family": "sherpa-qwen3",
        "conv_frontend": "conv_frontend.onnx", "encoder": "encoder.int8.onnx",
        "decoder": "decoder.int8.onnx", "tokenizer": "tokenizer",
        "num_threads": "8", "max_total_len": "600", "max_new_tokens": "256"
      },
      "source": { "kind": "sherpa-archive", "size_bytes": 878702423,
                  "urls": ["https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"] }
    },
    "zipa-ipa": {
      "properties": { "type": "ipa" },
      "source": { "kind": "loose-files", "files": [
        { "name": "model.int8.onnx", "minBytes": 62914560, "sha256": "e79c5ec3...", "urls": ["https://huggingface.co/anyspeech/zipa-small-crctc-ns-no-diacritics-700k/resolve/main/model.int8.onnx", "https://hf-mirror.com/anyspeech/..."] },
        { "name": "tokens.txt", "sha256": "f8e042a0...", "urls": [".../tokens.txt"] } ] }
    },
    "gtcrn-simple-denoiser": {
      "properties": { "type": "denoiser" },
      "source": { "kind": "loose-files", "files": [ { "name": "gtcrn_simple.onnx", "minBytes": 400000, "urls": ["..."] } ] }
    }
  },
  "mirrorProbe": { "enabled": true, "probeBytes": 262144, "timeoutMs": 5000, "minFileSizeBytes": 8388608 }
}
```

Key points:

- **One model = one engine**: the model name doubles as the engine id and the model directory; there is no separate `engines` section;
- **Per-language defaults follow declaration order**: the first declared model whose `lang` contains a language wins for that language (`/voicecast engine en` picks it);
- **`properties.type`**: `offline` (utterance ASR), `ipa` (phonemes), `denoiser` (auxiliary enhancement model — downloadable but never listed/selected as an engine); `properties.family` selects the engine family — derived by default only for the ipa kind, everything else (e.g. `sherpa-qwen3`) must be declared explicitly;
- **Mirror probing**: with multiple `source.urls` the server probes them concurrently (ranged GET, throughput-ranked) and downloads **fastest-first**; small files skip probing;
- **Self-hosting**: point `urls` at your own HTTP endpoints (LAN mirror, object storage);
- Manual placement: with `autoDownload=false` put extracted files under `config/voicecast/models/<modelName>/` (sherpa archives need a tokens equivalent + the `.onnx` files at the model root — a `tokens.txt`, or a `tokenizer/` directory for Qwen3-ASR).

## Diagnostics

For diagnostics add `-Dvoicecast.verbose=true` (or run with `-PvoicecastVerbose=true`) to log the recognition pipeline, or use `/voicecast status` / `/voicecast engine list`.

> [← Home](Home) · Previous: [Server Setup](Server-Setup) · Next: [Access Control](Access-Control)
