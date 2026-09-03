# Changelog — VoiceCast

English primary; Chinese mirror: [CHANGELOG.zh.md](CHANGELOG.zh.md) (keep both in sync, English wins on conflict).

## Unreleased

### Infrastructure

- Fabric dev runs bundle a Carpet testing mod for in-dev voice testing (Forge port blocked; voicecast#38)

## 0.3.2 — 2026-09-02

### Changes

- Engine ids unified to `vosk-<language>`: `vosk-en`, `vosk-cn`, `vosk-jp`, `vosk-kr`, `ipa-phonemes`; the `vosk-text` id is retired (plain English = `vosk-en`), and the full engine set is registered out of the box
- Simple Voice Chat coexistence is share-only: the experimental `defer` mode was removed; configuring `svcCoexistence = "defer"` falls back to `share` with a one-time warning (voicecast#27)
- Removed dead server config key `[server] opusBitrate` (voicecast#3)

### Bugfixes

- Dev `runServer` and `runClient` now use separate run directories (`run-server/`), so they can run concurrently on Windows
- Voice models are auto-seeded into run directories as hard links (zero copy; deleting a link never touches the workspace copy)

### Modding/API

- breaking: engine id scheme is now `vosk-<lang>` — configs and commands referencing `vosk-text` must switch to `vosk-en`

### Packaging

- IPA model ships q4 ONNX only (the float32 fallback was dropped); full-platform ONNX Runtime natives retained for dedicated servers
- `config/` gitignore rule scoped to run directories

### Infrastructure

- GitHub Actions build+test workflow (tag/PR triggers, foojay toolchain resolver); new issues auto-added to the Be a Real Wizard project; issue templates (bug/feature/config)
- Wiki split into VoiceCast-specific bilingual pages

## 0.3.1 — 2026-09-01

### Features

- Simple Voice Chat coexistence (M7b): VoiceCast always shares the microphone device; when SVC transmitted within the last 300 ms, a throttled info line notes the overlap (never blocks or mutes SVC)

### Changes

- Dead config fields removed (M7b W4)

### Modding/API

- SVC plugin registers loader-natively (Fabric entrypoint key `voicechat` / Forge `@ForgeVoicechatPlugin` annotation scan) — never ServiceLoader; the plugin class never instantiates when SVC is absent

### Packaging

- voicechat-api 2.6.20 is compileOnly and never bundled; `verifyFatJarPolicy` guards packaging rules (module-info stripping, JNA policy, Concentus presence)

### Infrastructure

- Repo split baseline housekeeping: GitHub issue templates, add-to-project workflow

## 0.3.0 — 2026-09-01 (workspace split baseline)

### Features

- Offline voice casting foundation: push-to-talk capture, energy VAD, streaming Vosk recognition (en/cn/ja/ko models), wav2vec2-espeak IPA phoneme engine, spell matching, on-screen transcript HUD with mic level meter
- Server-side recognition: clients stream Opus-encoded audio to the server and download no models (q4 IPA model ~230 MB lives on the server)
- Model manager with SHA-verified downloads and hf-mirror fallback
- Persistent client/server config (engine choice, HUD anchor, VAD thresholds, verbose logging)

### Modding/API

- Stable addon SPI `com.theo.voicecast.api`: `SpeechRecognizer`, `RecognizerRegistry`, `Pronunciation`, `VoiceCastEvents` (partial/final/state/audio-level events); public API free of vosk/JNA/ORT types

### Protocol

- `voicecast:audio` channel: Opus (Concentus) frame streaming c2s, state/transcript s2c; server-authoritative recognition sessions per player

### Packaging

- Architectury dual-loader (Fabric + Forge); JNA never relocated or shaded; Vosk/ORT natives bundled for win/linux/macos
