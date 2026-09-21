# [English](Getting-Started) | [中文](Getting-Started-zh)

# Getting Started (VoiceCast)

> [← Home](Home) · Next: [Server Setup](Server-Setup)

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
| **Utterance recognition** `qwen3-asr-0.6b-int8` | ~880 MB | Free-form spoken triggers and chant lines in en/zh/ja/ko/yue/de/fr/es/ru (default) |
| **Phoneme recognition (IPA)** `zipa-ipa` | ~70 MB | Pronouncing Latin/English/Chinese/Japanese incantations (phoneme matching) |

The model downloads **once** on first selection into `config/voicecast/models/` (sha256-verified). The Qwen3-ASR model comes from the official **sherpa-onnx GitHub release**; the **IPA (ZIPA)** model comes from HuggingFace with the hf-mirror.com mirror — you can add your own mirrors in `models.json`. Switch any time:

```
/voicecast engine en      # utterance recognition (Qwen3-ASR, default)
/voicecast engine zh      # same engine via the language route
/voicecast engine ipa     # IPA phoneme recognition (ZIPA)
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

> [← Home](Home) · Next: [Server Setup](Server-Setup)