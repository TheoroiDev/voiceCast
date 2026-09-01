# VoiceCast

A reusable, offline **voice-recognition library mod** for Minecraft 1.20.1 — the speech engine behind [Be a Real Wizard](https://github.com/Theoroi/wizardReal). Addon mods can register their own recognizer engines and pronunciations.

> This repository is one half of the split project: `voicecast` is a standalone library; the gameplay mod `wizardreal` depends on it via maven coordinates (`com.theo.voicecast:voicecast-{common,fabric,forge}-1.20.1`). Player & admin guides live in the wizardreal repo's [wiki](https://github.com/Theoroi/wizardReal/wiki)（中文 / English）.

## What it does

- External push-to-talk microphone capture at 16 kHz / 16-bit / mono — the mic opens only while a gameplay mod drives PTT (in the base setup: WizardReal, hold a staff and right-click), with a silence endpoint that finishes an incantation when you pause.
- Two offline recognizers (no cloud, no API key): **Vosk** word recognition (~40 MB) and **wav2vec2-espeak IPA phoneme recognition** (~230 MB int4) via ONNX Runtime.
- First-run model download with retry, checksum/size verification, and a China-friendly mirror (hf-mirror.com) fallback.
- Server-authoritative recognition: the client only streams Opus audio; models and inference live on the server, with localized status messages.
- Server access control: `[server] enabled` master switch + `[players]` UUID whitelist, with a pluggable `AccessCheck` hook for permission mods.
- A stable engine SPI: register your own recognizer (Whisper, cloud STT, ...) without touching gameplay code.

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
modImplementation "com.theo.voicecast:voicecast-fabric-1.20.1:0.3.0"   // fabric
modImplementation "com.theo.voicecast:voicecast-forge-1.20.1:0.3.0"    // forge
compileOnly       "com.theo.voicecast:voicecast-common-1.20.1:0.3.0"   // common codegen
```

## Addon developers

```java
RecognizerRegistry.register("my-engine", MyEngine::new);
```

The public API (`com.theo.voicecast.api`) has **no** reference to `org.vosk` / `com.sun.jna` / `ai.onnxruntime` — compile against the published artifacts; the bundled implementation is provided at runtime. See [AGENTS-voicecast.md](../AGENTS-voicecast.md) for packaging/classloader rules.

## License

MIT (see [`LICENSE`](LICENSE)). Bundled third-party components keep their own licenses and ship inside every jar as `META-INF/NOTICE` + `META-INF/legal/THIRD-PARTY-NOTICES.md`: Vosk + libvosk (Apache-2.0), ONNX Runtime (MIT), Concentus (MIT), JNA (Apache-2.0 / LGPL-2.1-or-later). Full attribution: CREDITS.md at the workspace root; notices ship inside the jars (legal/).
