# VoiceCast — common

Platform-independent VoiceCast code. This module is the whole library: API, recognizer engines, matching/adjudication, microphone capture, model management, server-side recognition orchestration. The Fabric and Forge subprojects only register events/entrypoints and call into here.

## Key packages (`com.theo.voicecast.*`)

| Package/class | Purpose |
|---------------|---------|
| `api/SpeechRecognizer` | Engine SPI: `start`, `stop`, `acceptPcm`, `finishUtterance`, `setVocabulary`; `lastDiagnostics()` exposes engine-internal numbers of the last adjudication (diagnostics only). |
| `api/RecognizerRegistry` | Recognizer backends by id. Builtin registrations: `noop`, `zipa-ipa`, `qwen3-asr-0.6b-int8` (default). Selectable engine ids are the `models.json` v2 catalog model names (one model = one engine); family extension goes through `api/engine/EngineFamilies`. |
| `api/SessionVocabulary` | The single vocabulary push entry point (contract v2): spell ids × language aliases × IPA templates × optional per-entry `ThresholdHint`. |
| `api/RecognitionResult` / `SpeechOptions` | Semantic result record (contract v2): utterance text + IPA + language + `Decision` (EXACT/NEAR/AMBIGUOUS/REJECTED) + winning spell/pronunciation ids + score + up-to-3 alternatives; `SpeechOptions` carries engine configuration (+ the `Calibration` component). |
| `api/VoiceCastEvents` | Tiny pub/sub: partial/final result, audio level, recognizer state. |
| `client/VoiceCastClient` | Client controller: mic capture gated by external PTT (WizardReal staff+right-click), Opus encode + streaming to the server. |
| `client/EnginePicker`, `client/EngineSelectScreen` | Engine preference persisted in `voicecast.toml`, `/voicecast` client command, picker screen (Mod Menu / Forge Mods config); the selectable id list is generated from the models.json catalog. |
| `client/hud/VoiceCastHud` | HUD rendering (waveform, transcript, localized status line); screen anchors are internal constants (configurable per-HUD anchors are a planned feature). |
| `engine/SherpaQwen3Recognizer` | Offline multilingual utterance engine (default; catalog id `qwen3-asr-0.6b-int8`, en/zh/ja/ko/yue/de/fr/es/ru): sherpa-onnx `OfflineQwen3AsrModelConfig`, session hotword biasing (≤100 entries) with an automatic hotword-free second decode when a transcript comes back empty. |
| `engine/ZipaPhonemeRecognizer`, `ZipaShared`, `KaldiFbank` | IPA phoneme engine (catalog id `zipa-ipa`): ZIPA CR-CTC int8 via direct ONNX Runtime + a Java kaldi-fbank port (ZIPA Route B); template shapes are derived engine-internally from `SessionVocabulary`. |
| `engine/AbstractBufferedRecognizer`, `NoopRecognizer`, `MiniJson` | Shared engine scaffolding. |
| `match/SpellMatcher`, `PhonemeMatcher`, `Phonetics`, `UtteranceAdjudicator` | Recognition matching + fusion adjudication (semantic contract v2): text-alias scoring, IPA phoneme matching with the confusion-cost asset `assets/voicecast/phoneme_costs.tsv`, CTC template scoring + margin rejection; calibration defaults live in the `[match]` config section, per-entry overrides arrive as `ThresholdHint` data. |
| `server/AccessPolicy` + `api/AccessCheck` | Pure access decision for voice streaming (master switch / UUID whitelist / pluggable permission hook); unit-tested. |
| `audio/MicCapture`, `OpusAudioCodec`, `NoiseSuppression`, `WavDumper` | PTT-gated PCM capture, Opus (Concentus) encode/decode, optional streaming GTCRN mic denoising (default off), debug WAV writer. |
| `model/ModelManager`, `ModelConfig`, `SherpaModel`, `ZipaModel`, `NativeLoader` | `models.json` v2 catalog (model = engine), download/SHA-or-size verification/extract (zip + tar.bz2/tgz), per-engine model directories; all-platform native loading. |
| `config/ServerConfig` | Server config (`[server]`/`[engines]`/`[players]`/`[match]` sections of `voicecast.toml`). |
| `config/ClientVoiceConfig`, `VoiceCastConfig` | Client config (`[client]`: engine, noiseSuppression, …) and client-only constants (transcript HUD, silence endpoint, verbose log). |

## Packaging note

This project's `jar` (classifier `dev`, output to `build/devlibs/`) is a **fat jar** that bundles the sherpa-onnx JVM API + all-platform natives, ONNX Runtime classes and all-platform ONNX natives, and Concentus. It deliberately does **not** bundle JNA:

- JNA cannot be shadow-relocated — its `jnidispatch` native library is bound to the original `com.sun.jna.*` symbol names.
- Forge/Architectury already provides `jna:5.12.1` at runtime; Fabric gets it from the platform project.

Third-party packages are not relocated (sherpa-onnx stays at `com.k2fsa.sherpa.onnx`, ONNX Runtime at `ai.onnxruntime.*`, Concentus at `io.github.jaredmdobson.concentus`).

The fat jar also bundles ONNX Runtime (`ai.onnxruntime.*`) with **all-platform** native libraries so dedicated servers can run on Linux/arm64/macOS; the ZIPA phoneme engine loads `model.int8.onnx` from the catalog. See [AGENTS-voicecast.md (workspace root)](../../../AGENTS-voicecast.md) for the full set of build rules before touching dependencies.

## Outputs

- `build/devlibs/voicecast-common-1.20.1-<version>-dev.jar` — dev runtime jar (fat).
- `build/libs/voicecast-common-1.20.1-<version>-transformProduction{Fabric,Forge}.jar` — production artifacts consumed by the platform shadow jars.
