# VoiceCast — Forge (MC 1.20.1 Forge 47.x)

Forge loader wiring for [VoiceCast](../../README.md).

## Contents (`com.theo.voicecast.forge`)

- `VoiceCastForge` — `@Mod` entry point; common init + soft-dependency detection.
- `VoiceCastForgeClient` — client setup:
  - registers the HUD overlay via `RegisterGuiOverlaysEvent.registerAboveAll`;
  - runs the first client tick init (`VoiceCastClient.init()` → S2C receivers, `/voicecast` command).
- PTT is driven externally by gameplay mods (WizardReal: hold a staff and right-click) — no keybind is registered here.

## Run / build

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\gradlew :voicecast-forge:runClient     # dev run
.\gradlew :voicecast-forge:build         # produces build/libs/*.jar
```

## Classloader notes

Forge 1.20.1 dev runs on a module-path-aware `ModuleClassLoader`. The working setup:

- Vosk classes + `libvosk` natives ride inside the bundled `voicecast-common` fat jar (same classloader as the engine code).
- **Do not** add JNA here — Architectury/Forge already provides `jna:5.12.1`. A second copy on the module path crashes startup with `ResolutionException: Modules com.sun.jna ... export package ...`.
- **Do not** relocate JNA (its native `jnidispatch` symbols are bound to `com.sun.jna.*`).

See [AGENTS-voicecast.md and docs/STAGE1_SUMMARY.md (workspace root)](../../../AGENTS-voicecast.md) for the full set of rules and failure modes.

## Resources

- `META-INF/mods.toml` — Forge mod metadata (`modId = voicecast`).
- `pack.mcmeta` — pack format 15.
