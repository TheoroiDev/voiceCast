# [English](Configuration) | [中文](Configuration-zh)

# Configuration Reference

> [← Home](Home) · Previous: [Server Setup](Server-Setup) · Next: [Access Control](Access-Control)

VoiceCast uses **one shared config file** (client/server read their own sections):

- `<gameDir>/config/voicecast/voicecast.toml` — switches, engines, whitelist
- `<gameDir>/config/voicecast/models.json` — model catalog & mirrors
- Model files: `config/voicecast/models/<modelId>/`

The file is auto-created on first load and rewritten (versioned, missing keys get defaults); legacy `server.properties` / `client.properties` are imported once and deleted. Restart the server after edits.

## voicecast.toml

```toml
version = 1

[server]
defaultEngine = "vosk-text"   # vosk-text | vosk-en | vosk-cn | vosk-jp | vosk-kr | ipa-phonemes | noop
autoDownload = true           # allow server-side model downloads
maxFramesPerSecond = 15       # per-session audio frame cap (abuse guard)
enabled = true                # master switch: false = nobody may stream

[engines]
allowed = ["vosk-text", "vosk-en", "vosk-cn", "vosk-jp", "vosk-kr", "ipa-phonemes"]

[players]
whitelist = []                # array of UUID strings; empty = everyone

[compat]
svcCoexistence = "share"      # share | defer  (Simple Voice Chat coexistence)

[client]                      # ← player-local section
engine = "vosk-text"
```

| Key | Meaning |
|---|---|
| `[server] defaultEngine` | Engine pre-warmed at startup; also the fallback for players who haven't chosen |
| `[server] autoDownload` | `false` disables all downloads; a missing model reports `NO_MODEL` (place files manually) |
| `[server] maxFramesPerSecond` | Max audio frames per player per second; excess frames are dropped (anti-spam) |
| `[server] enabled` | **Master switch**. `false`: no model warm-up, all audio frames silently dropped, players get a one-time "disabled" notice |
| `[engines] allowed` | Whitelist of selectable engines (rejected selections report "engine not allowed"). Use it to stop players from triggering big downloads |
| `[players] whitelist` | UUID array (invalid UUIDs are skipped with a warning). **Empty = everyone**; non-empty = only listed players may stream. Order of checks: [Access Control](Access-Control) |
| `[compat] svcCoexistence` | Simple Voice Chat coexistence mode (**client-local**: each player's own config; the server neither reads nor syncs it) — see [Simple Voice Chat Integration](Simple-Voice-Chat-Integration) |
| `[client] engine` | Player-local engine preference. Valid: `vosk-text` / `vosk-en` / `vosk-cn` / `vosk-jp` / `vosk-kr` / `ipa-phonemes` (command aliases vosk/en/zh/ja/ko/ipa… are normalized) |

> The former `[server] opusBitrate` key has been **removed**: the Opus encoder runs on the client, so a server-side key could never reach it (that would require a new sync channel). Bandwidth is ~3 KB/s per speaking player (see [Performance](Performance.md)). Stale `opusBitrate` entries in existing configs are ignored.

> CJK engines: `vosk-cn` / `vosk-jp` / `vosk-kr` are fully registered (native-language Vosk recognition). Each selected language downloads and keeps its own shared model (~40–90 MB disk, ~150–250 MB RAM while loaded) — they load lazily, only for languages players actually use. Note for Japanese: the ja model outputs kana/kanji text, so spell aliases written in romaji may not match on the text path; prefer kana aliases or the `ipa-phonemes` engine.

## models.json (model catalog)

Auto-generated and **user-overridable** (merged per key; missing entries get defaults). Structure:

```json
{
  "version": 1,
  "models": {
    "vosk-model-small-en-us-0.15": {
      "kind": "vosk-archive",
      "sizeBytes": 41205931,
      "sha256": "30f26242c4eb...",
      "urls": ["https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"]
    },
    "wav2vec2-espeak-ipa": {
      "kind": "loose-files",
      "files": {
        "vocab.json":    { "minBytes": 1,       "urls": ["https://hf-mirror.com/...", "https://huggingface.co/..."] },
        "model_q4.onnx": { "minBytes": 150000000, "urls": [".../model_q4.onnx", ".../model_q4.onnx"] }
      }
    }
  },
  "engines": {
    "vosk-text":   { "model": "vosk-model-small-en-us-0.15" },
    "vosk-en":     { "model": "vosk-model-small-en-us-0.15" },
    "vosk-cn":     { "model": "vosk-model-small-cn-0.22" },
    "vosk-jp":     { "model": "vosk-model-small-ja-0.22" },
    "vosk-kr":     { "model": "vosk-model-small-ko-0.22" },
    "ipa-phonemes": { "model": "wav2vec2-espeak-ipa" }
  },
  "mirrorProbe": { "enabled": true, "probeBytes": 262144, "timeoutMs": 5000, "minFileSizeBytes": 8388608 }
}
```

Key points:

- **Mirror probing**: with multiple `urls` per file the server probes them concurrently (ranged GET, throughput-ranked), downloads **fastest-first** with the rest as fallbacks; files under 8 MB skip probing;
- **Self-hosting**: point `urls` at your own HTTP endpoints (LAN mirror, object storage);
- The **IPA model** ships as the q4-quantized `model_q4.onnx` only (~150 MB) — there is no f32 fallback;
- Manual placement: with `autoDownload=false` put files under `config/voicecast/models/<modelId>/`; extracted Vosk needs `am/ conf/ graph/`.

## Player-side settings

The only player-writable key is `[client] engine`; everything else (HUD toggles, silence-endpoint thresholds…) is a code constant. For diagnostics add `-Dvoicecast.verbose=true` to log the recognition pipeline.
