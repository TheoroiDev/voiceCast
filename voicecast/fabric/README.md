# VoiceCast — Fabric

Fabric loader wiring for [VoiceCast](../../README.md).

## Contents (`com.theo.voicecast.fabric`)

- `VoiceCastFabric` — mod initializer (registers mod metadata, common init).
- `VoiceCastFabricClient` — client initializer:
  - registers the HUD render via Fabric `HudRenderCallback`;
  - runs the first client tick init (`VoiceCastClient.init()` → S2C receivers, `/voicecast` command).
- PTT is driven externally by gameplay mods (WizardReal: hold a staff and right-click) — no keybind is registered here.

## Run / build

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\gradlew :voicecast-fabric:runClient     # dev run
.\gradlew :voicecast-fabric:build         # produces build/libs/*.jar
```

Dependencies: Fabric Loader, Fabric API, Architectury. JNA (`net.java.dev.jna:jna:5.12.1`) is added here because Fabric itself does not ship it; Vosk classes/natives come from the bundled `voicecast-common` fat jar.

## Resources

- `fabric.mod.json` — entrypoints `main` / `client`; `environment: "*"`.
- `assets/voicecast/lang/en_us.json` — keybind and (future) config names.
- `pack.mcmeta` — pack format 15 (1.20.1).
