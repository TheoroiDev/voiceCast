# [English](Troubleshooting) | [中文](Troubleshooting-zh)

# Troubleshooting (VoiceCast)

> [← Home](Home)

## No waveform HUD

1. The waveform appears when a gameplay mod drives push-to-talk (base setup: **hold a staff + right-click**);
2. You must be **inside a world**;
3. Both mods installed (voicecast + the gameplay mod).

## Waveform shows red static

Red = the model isn't ready. Read the **status line** above the waveform:

- **Gold** "preparing/downloading model": first download, wait a moment (Vosk ~40 MB / IPA ~150 MB);
- **Red** "engine failed / model missing": check `logs/latest.log`. If the server set `autoDownload = false`, an admin must place the model files manually ([Configuration](Configuration));
- **Red** "Microphone unavailable": next section.

## "Microphone unavailable" / nothing gets recognized

1. Check the OS **microphone privacy permission** for Java/Minecraft;
2. Verify the system's default recording device (VoiceCast uses the default input device);
3. Exclusive-mode mic access by other apps (Discord, OBS) can conflict — disable exclusive mode (Windows: device properties → Advanced);
4. Simple Voice Chat / Plasmo Voice coexistence is handled automatically — see [Simple Voice Chat Integration](Simple-Voice-Chat-Integration).

## My trigger word doesn't match

- Slow down and pronounce clearly;
- **`vosk-text` only understands English** — for Chinese/Japanese use the native-language engines `vosk-cn` / `vosk-jp` (Korean: `vosk-kr`); the IPA engine is the phoneme-matching alternative;
- The IPA engine matches by phonemes and is forgiving to non-native accents (tolerates tense/lax vowel shifts, dropped syllable-final consonants);
- The gray text under the crosshair is the live recognition — if it's far from any trigger word, first confirm the model finished loading (red status line gone).

## Switching engines

```
/voicecast engine vosk    # word recognition (English, vosk-text)
/voicecast engine zh      # word recognition (Chinese, vosk-cn)
/voicecast engine ja      # word recognition (Japanese, vosk-jp)
/voicecast engine ko      # word recognition (Korean, vosk-kr)
/voicecast engine ipa     # phoneme recognition
/voicecast settings       # picker UI
```

Applies immediately; remembered in `config/voicecast/voicecast.toml` under `[client] engine`. The server may restrict available engines ("engine not allowed" → ask the admin).

## Model download fails (server)

- Proxy: `-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>` (the downloader also detects `HTTPS_PROXY`);
- Self-host / change mirrors: `config/voicecast/models.json`;
- Fully offline: `[server] autoDownload = false` + manual placement — details in [Configuration](Configuration).

## Advanced diagnostics

- Start with `-Dvoicecast.verbose=true` to log the recognition pipeline (`[Mic]`, `[Vosk]`, `[IPA DEBUG]`);
- Logs: `logs/latest.log`; crashes: `crash-reports/`;
- A client-side debug WAV recording (`VoiceCastConfig.saveDebugWav` source constant, off by default) proves whether capture works — separate from whether recognition works.
