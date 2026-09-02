# VoiceCast — common

Platform-independent VoiceCast code. This module is the whole library: API, recognizer engines, microphone capture, model management, server-side recognition orchestration. The Fabric and Forge subprojects only register events/entrypoints and call into here.

## Key packages (`com.theo.voicecast.*`)

| Package/class | Purpose |
|---------------|---------|
| `api/SpeechRecognizer` | Engine SPI: `start`, `stop`, `acceptPcm`, `finishUtterance`, `setVocabulary`. |
| `api/RecognizerRegistry` | Engines by id. Builtin: `noop`, `vosk-text` (default), `vosk-en`, `vosk-cn`, `vosk-jp`, `vosk-kr`, `ipa-phonemes`. Engine ids follow `vosk-<two-letter language>` for Vosk language engines. |
| `api/Pronunciation` | A recognized word: id, text aliases (Vosk grammar), IPA templates (phoneme matching). |
| `api/RecognitionResult` / `SpeechOptions` | Result text + IPA tokens + confidence; engine configuration. |
| `api/VoiceCastEvents` | Tiny pub/sub: partial/final result, audio level, recognizer state. |
| `client/VoiceCastClient` | Client controller: mic capture gated by external PTT (WizardReal staff+right-click), Opus encode + streaming to the server. |
| `client/EnginePicker`, `client/EngineSelectScreen` | Engine preference persisted in `voicecast.toml`, `/voicecast` client command, picker screen (Mod Menu / Forge Mods config). |
| `client/hud/VoiceCastHud` | HUD rendering (waveform, transcript, localized status line); screen anchors are internal constants (configurable per-HUD anchors are a planned feature). |
| `engine/VoskTextRecognizer` | Vosk word engine (grammar mode with full-graph fallback); ids `vosk-text` (default) and the CJK language variants `vosk-cn` / `vosk-jp` / `vosk-kr` (legacy ids `vosk-en-us` / `vosk-zh-cn` / `vosk-ja-jp` / `vosk-ko-kr` are auto-migrated by config normalize) — dispatch is by the models.json model kind, so all Vosk languages share this class. |
| `engine/IpaPhonemeRecognizer` | IPA phoneme engine (id `ipa-phonemes`): wav2vec2-lv-60-espeak-cv-ft via ONNX Runtime; buffers an utterance and decodes off-thread to IPA tokens. |
| `engine/AbstractBufferedRecognizer`, `NoopRecognizer`, `MiniJson` | Shared engine scaffolding. |
| `server/AccessPolicy` + `api/AccessCheck` | Pure access decision for voice streaming (master switch / UUID whitelist / pluggable permission hook); unit-tested. |
| `audio/MicCapture`, `OpusAudioCodec`, `WavDumper` | PTT-gated PCM capture, Opus (Concentus) encode/decode, debug WAV writer. |
| `model/ModelManager`, `VoskModel`, `IpaModel`, `NativeLoader` | Download/SHA/unzip/flatten; per-engine model metadata. |
| `config/ServerConfig` | Server config (`[server]`/`[engines]`/`[players]` sections of `voicecast.toml`). |
| `config/VoiceCastConfig` | Client-only config constants (transcript HUD, silence endpoint, verbose log). |

## Packaging note

This project's `jar` (classifier `dev`, output to `build/devlibs/`) is a **fat jar** that bundles Vosk classes, the `libvosk` native libraries, ONNX Runtime classes and all-platform ONNX natives, and Concentus. It deliberately does **not** bundle JNA:

- JNA cannot be shadow-relocated — its `jnidispatch` native library is bound to the original `com.sun.jna.*` symbol names.
- Forge/Architectury already provides `jna:5.12.1` at runtime; Fabric gets it from the platform project.

Keep Vosk at its original `org.vosk` package.

The fat jar also bundles ONNX Runtime (`ai.onnxruntime.*`, not relocated) with **all-platform** native libraries so dedicated servers can run on Linux/arm64/macOS. The IPA engine uses the official q4 ONNX model (the int8 transformer.js export relies on `ConvInteger`, unsupported by ORT CPU). See [AGENTS-voicecast.md (workspace root)](../../../AGENTS-voicecast.md) for the full set of build rules before touching dependencies.

## Outputs

- `build/devlibs/voicecast-common-1.20.1-<version>-dev.jar` — dev runtime jar (fat).
- `build/libs/voicecast-common-1.20.1-<version>-transformProduction{Fabric,Forge}.jar` — production artifacts consumed by the platform shadow jars.
