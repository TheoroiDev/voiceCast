# Changelog — VoiceCast

English primary; Chinese mirror: [CHANGELOG.zh.md](CHANGELOG.zh.md) (keep both in sync, English wins on conflict).

## Unreleased

### Features

- Optional microphone noise suppression (`[client] noiseSuppression`, default off): streaming GTCRN speech enhancement (sherpa-onnx, 16 kHz native, ~523 KB model auto-downloaded through the catalog) applied to the recognition path only — what other players hear through Simple Voice Chat is unaffected; any denoiser failure degrades to clean passthrough
- models.json v2 catalog — the model catalog IS the engine list (voicecast#42): one model = one engine, the model name doubles as the engine id and the model directory name; per-language defaults follow declaration order (the first declared model supporting a language wins); selection accepts model names and two-letter language codes / common language names (`/voicecast engine en`, `zh`, `japanese`); addons can declare custom engine families via `properties.family` + the engine family SPI; the engine selection screen and `/voicecast engine list` are generated from the catalog

### Changes

- breaking: models.json schema v2 nests per-model `properties` (lang/type/options) and `source` (kind/urls/files) and drops the separate `engines` section — no migrations (v0 policy, AGENTS §3): files not in v2 shape are rewritten with the default catalog, and old engine ids (`sherpa-zh-en`, `sherpa-sensevoice`, `ipa-phonemes`, all vosk ids) no longer resolve — use catalog model names (`sherpa-zipformer-bilingual-zh-en-int8`, `sherpa-sensevoice-small-int8`, `wav2vec2-espeak-ipa`)
- breaking: Vosk removed and replaced by sherpa-onnx models (voicecast#42): `sherpa-zipformer-bilingual-zh-en-int8` — streaming zipformer bilingual zh/en (default) — and `sherpa-sensevoice-small-int8` — offline SenseVoice covering zh/yue/en/ja/ko as the short-utterance high-accuracy option (now sourced from the ~230 MB int8-only archive instead of the 1.1 GB full archive); the IPA phoneme model is unchanged. `sherpa-sensevoice-full` (fp32 weights from the full ~1.1 GB archive) ships alongside the int8 entry for A/B comparison. Models download on demand through the catalog
- breaking: config semantics simplified - `[client] engine` empty means "catalog default"; `[server] defaultEngine` accepts a model name or language code (empty = catalog default); `[engines] allowed` empty means every catalog model is allowed; no alias normalization or legacy imports remain in configs

### Bugfixes

- Joining a world threw `String too big` whenever the saved engine id exceeded 32 characters (v2 model names run up to 43+): the exception aborted the login-packet handler, which also killed fabric's client command dispatcher setup — every `/voicecast` client command then failed with `NullPointerException ... activeDispatcher is null` and client commands vanished from chat autocomplete. The engine-select channel now carries up to 256 characters and the join hook can no longer break sibling handlers
- The streaming recognizer failed to start with "Invalid OnlineRecognizerConfig: failed to create native OnlineRecognizer" whenever spell hotwords were active: `cjkchar+bpe` hotword encoding requires the model's `bpe.vocab`, which was never passed to sherpa. It is now wired (models.json `bpe_vocab` property), and a missing file degrades to hotword-free open-vocabulary decoding instead of failing the recognizer (voicecast#42) "Invalid OnlineRecognizerConfig: failed to create native OnlineRecognizer" whenever spell hotwords were active: `cjkchar+bpe` hotword encoding requires the model's `bpe.vocab`, which was never passed to sherpa. It is now wired (models.json `bpe_vocab` property), and a missing file degrades to hotword-free open-vocabulary decoding instead of failing the recognizer (voicecast#42)
- sherpa's config builders default to `debug=true` (dumping internal state); recognizer output is quiet unless dev verbosity is enabled (`gradlew runClient -PvoicecastVerbose=true` or `/voicecast verbose`, applied at recognizer (re)build)
- The model download HUD sat frozen on "999 MB" through ~100 MB stretches of large archives (GB display granularity); MB display is kept up to 10 GB so the counter visibly ticks
- Client-only commands (`/voicecast settings`, `verbose`, `debugwav`) were executable but missing from chat autocomplete: the server-side `/voicecast` admin tree shadowed the synced completion list; level-0 server stubs now merge the client-only children into the completion tree on both Fabric and Forge
- sherpa model downloads actually extract now: the downloader only unpacked `.zip` archives (the vosk format), so sherpa `tar.bz2` archives were left un-extracted and every load failed with "Downloaded model is missing expected files"; tar.bz2 / tgz / plain tar are handled too, and the nested top-level directory inside these archives is hoisted into the model root (voicecast#42)
- The model download HUD said "Downloading Vosk model" for sherpa downloads; it now says "Downloading model"

### Modding/API

- breaking: `Pronunciation` gained per-language alias buckets (`languages()`, two-letter codes en/zh/ja/ko); the flat constructor still works (deprecated) and its aliases form the legacy bucket routed into every engine's grammar; the server routes each session's vocabulary by the selected engine's language - the engine decides the bucket
- breaking: engine ids are catalog model names; deprecated `ENGINE_*` constants and the normalize alias table are gone - resolve against `ModelConfig.resolveModel(...)` (exact name, then language code by declaration order)
- new: engine family SPI - `EngineFamilies.register(type, RecognizerFactory)` (`com.theo.voicecast.api.engine`) lets addon mods register their own recognizer families, selectable through `properties.family` in models.json; built-in families: `sherpa-streaming`, `sherpa-sensevoice`, `ipa` (voicecast#19)

### Protocol

- The `audio/select` frame (client → server engine selection) carries engine ids of up to 256 characters — v2 model names exceed the previous 32-character limit; client and server already need matching versions

### Infrastructure

- Engine classes (IpaShared/IpaPhonemeRecognizer/VoskTextRecognizer/AbstractBufferedRecognizer/MiniJson/NoopRecognizer) no longer route logging through the mod class - they carry their own slf4j loggers, so the engine runs standalone (voicecast fat jar) for the wizardreal ipa backtest tools
- Dev-only testing mods moved out of gradle: release jars are pre-downloaded under workspace `resources/devmods/<loader>/` and wired from `manifest.txt` (fabric: hardlinked into the run mods folder; forge: file dependency so Loom remaps the SRG jar; Forge port of Carpet stays blocked, voicecast#38); voice-model fact source moved to `resources/models/`
- Dev model seeding is manifest-driven: workspace `resources/models/manifest.txt` lists the model dirs hardlinked into the run dirs (one per line, commented-out = disabled; absent manifest seeds everything) - the vosk models are commented out there in step with the vosk removal
- The vosk dependency (compileOnly + fat-jar bundling) is gone from the build; tar extraction uses the commons-compress the Minecraft runtime already ships (compile-only, never bundled)

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
