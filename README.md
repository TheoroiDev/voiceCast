# VoiceCast

A reusable, offline **voice-recognition library mod** for Minecraft 1.20.1 — the speech engine behind [Be a Real Wizard](https://github.com/TheoroiDev/wizardReal). Addon mods can register their own recognizer engines and pronunciations.

> This repository is one half of the split project: `voicecast` is a standalone library; the gameplay mod `wizardreal` depends on it via maven coordinates (`com.theo.voicecast:voicecast-{common,fabric,forge}-1.20.1`). Player & admin guides live in the wizardreal repo's [wiki](https://github.com/TheoroiDev/wizardReal/wiki)（中文 / English）.

## What it does

- External push-to-talk microphone capture at 16 kHz / 16-bit / mono — the mic opens only while a gameplay mod drives PTT (in the base setup: WizardReal, hold a staff and right-click), with a silence endpoint that finishes an incantation when you pause.
- Two offline recognizers (no cloud, no API key): **Vosk** word recognition (~40 MB) and **wav2vec2-espeak IPA phoneme recognition** (~150 MB q4) via ONNX Runtime.
- First-run model download with retry and checksum/size verification — Vosk models come from **alphacephei.com** by default; the **hf-mirror.com** mirror serves the IPA (wav2vec2) model, and you can add your own mirrors for Vosk models in `models.json`.
- Server-authoritative recognition: the client only streams Opus audio; models and inference live on the server, with localized status messages.
- Server access control: `[server] enabled` master switch + `[players]` UUID whitelist, with a pluggable `AccessCheck` hook for permission mods.
- An engine SPI (**planned feature**): the `RecognizerRegistry.register(...)` interface is ready, but server-side engine selection/creation is not yet wired to the registry — only the built-in engine ids are selectable today (tracked in the voicecast issue tracker).

## Subprojects

- [`common/`](voicecast/common/README.md) — all real code (API, engine, mic, model management, server orchestration).
- [`fabric/`](voicecast/fabric/README.md) — Fabric loader wiring.
- [`forge/`](voicecast/forge/README.md) — Forge loader wiring.

## Build & dev

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"   # Gradle runs on JDK 21
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\gradlew build                    # build everything (incl. unit tests)
.\gradlew :voicecast-fabric:runClient   # dev run (user `dev`)
.\gradlew publishToMavenLocal      # publish for local wizardreal development
```

## Maven artifacts

`gradlew publishToMavenLocal` (or CI publishing) produces:

| Coordinate | Contents |
|---|---|
| `com.theo.voicecast:voicecast-common-1.20.1:<version>` | platform-independent fat dev jar (bundles Vosk/ONNX/Concentus classes + natives) — compile/test dependency |
| `com.theo.voicecast:voicecast-fabric-1.20.1:<version>` | production Fabric mod jar |
| `com.theo.voicecast:voicecast-forge-1.20.1:<version>` | production Forge mod jar |

Downstream consumption (wizardreal):

```gradle
// repositories: mavenLocal() (+ CI maven repo later)
modImplementation "com.theo.voicecast:voicecast-fabric-1.20.1:0.3.2"   // fabric
modImplementation "com.theo.voicecast:voicecast-forge-1.20.1:0.3.2"    // forge
compileOnly       "com.theo.voicecast:voicecast-common-1.20.1:0.3.2"   // common codegen
```

## Addon developers

```java
RecognizerRegistry.register("my-engine", MyEngine::new);
```

> **Planned feature** — the `RecognizerRegistry.register(...)` interface exists, but server-side engine selection/creation is not yet wired to the registry: only the built-in engine ids are selectable today. Progress is tracked in the voicecast issue tracker.

The public API (`com.theo.voicecast.api`) has **no** reference to `org.vosk` / `com.sun.jna` / `ai.onnxruntime` — compile against the published artifacts; the bundled implementation is provided at runtime. See [AGENTS-voicecast.md](../AGENTS-voicecast.md) for packaging/classloader rules.

## License

MIT (see [`LICENSE`](LICENSE)). Bundled third-party components keep their own licenses and ship inside every jar as `META-INF/legal/NOTICE` + `META-INF/legal/THIRD-PARTY-NOTICES.md`: Vosk + libvosk (Apache-2.0), ONNX Runtime (MIT), Concentus (MIT), JNA (Apache-2.0 / LGPL-2.1-or-later). Full attribution: [`legal/THIRD-PARTY-NOTICES.md`](legal/THIRD-PARTY-NOTICES.md) in this repository; notices ship inside the jars.
