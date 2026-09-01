# [English](Getting-Started) | [中文](Getting-Started-zh)

# Getting Started (VoiceCast)

> [← Home](Home) · Next: [Configuration](Configuration)

VoiceCast is a **library mod**: recognition runs on the **server**, players only stream audio. It powers [Be a Real Wizard](https://github.com/TheoroiDev/wizardReal) — without a gameplay mod attached, it still runs as an engine host for addons.

## Install

| Platform | Jar | Requires |
|---|---|---|
| Fabric | `voicecast-fabric-*.jar` | Fabric Loader, Fabric API, Architectury API |
| Forge | `voicecast-forge-*.jar` | Forge 47.x, Architectury API |

Client **and** server both get the jar. VoiceCast declares Simple Voice Chat / Plasmo Voice as *optional* — it coexists with them (see [Simple Voice Chat Integration](Simple-Voice-Chat-Integration)).

## Pick a recognition engine

Via the Mod Menu / Mods-list **config button**, or the `/voicecast settings` command:

| Engine | Size | Best for |
|---|---|---|
| **Word recognition (Vosk)** `vosk-text` | ~40 MB | Speaking English trigger words |
| **Phoneme recognition (IPA)** `ipa-phonemes` | ~230 MB | Pronouncing Latin/English/Chinese/Japanese incantations |

The model downloads **once** on first selection (hf-mirror.com first, huggingface.co fallback, sha256-verified) into `config/voicecast/models/`. Switch any time:

```
/voicecast engine vosk    # word recognition
/voicecast engine ipa     # phoneme recognition
```

## First recognition

1. Join a world (the waveform HUD appears when a gameplay mod drives push-to-talk — in the base setup: WizardReal, hold a staff and right-click);
2. Speak — the green waveform follows your voice, gray italic text shows live recognition, the final result appears in white quotes;
3. A gold/red **status line** above the waveform reports model downloads and errors in your game language.

## Model hosting (servers without internet)

- Proxy via JVM flags: `-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>`;
- Or self-host: edit `config/voicecast/models.json` and point `urls` at your own HTTP endpoints;
- Or fully offline: `[server] autoDownload = false` + place models manually — details in [Configuration](Configuration).

## Next steps

- Server admin? Read [Server Setup](Server-Setup) and [Access Control](Access-Control).
- Performance planning: [Performance](Performance).
- Problems: [Troubleshooting](Troubleshooting).
