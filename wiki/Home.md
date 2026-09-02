# [English](Home) | [中文](Home-zh)

# VoiceCast — Wiki

A reusable, offline **voice-recognition library mod** for Minecraft 1.20.1 (Fabric + Forge). It powers [Be a Real Wizard](https://github.com/TheoroiDev/wizardReal) and can be embedded by any addon mod.

> Recognition runs **server-side** — players never download models.

## Quick facts

- **Engines**: Vosk word recognition (~40 MB) / wav2vec2-espeak IPA phoneme recognition (~150 MB q4)
- **Model hosting**: auto-download, sha256-verified, fully self-hostable — Vosk models come from alphacephei.com by default, the IPA (wav2vec2) model uses the hf-mirror.com mirror, and you can add your own mirrors in `models.json`
- **Server access control**: `[server] enabled` + `[players]` UUID whitelist + pluggable `AccessCheck` hook
- **Simple Voice Chat**: first-class coexistence integration (share/defer) — see [Simple Voice Chat Integration](Simple-Voice-Chat-Integration)
- **Engine SPI** (planned): the `RecognizerRegistry.register(...)` hook is ready, but server-side engine selection is not yet wired to the registry — only built-in engine ids are selectable today (see the voicecast issue tracker)

## Documentation (English)

| Page | Contents |
|---|---|
| [Getting Started](Getting-Started) | Install, engine choice, model download, first recognition |
| [Server Setup](Server-Setup) | How server-side recognition works, proxies, memory sizing |
| [Configuration](Configuration) | Full `voicecast.toml` + `models.json` reference |
| [Access Control](Access-Control) | Master switch, UUID whitelist, permission hook |
| [Performance](Performance) | Capacity cheat sheet, memory/CPU profile |
| [Simple Voice Chat Integration](Simple-Voice-Chat-Integration) | Coexistence modes (share/defer), plugin architecture |
| [Troubleshooting](Troubleshooting) | Microphone, red waveform, downloads, engine switching |

## Documentation (中文)

| 页面 | 内容 |
|---|---|
| [首页（中文）](Home-zh) | 总览与快速开始 |
| [入门](Getting-Started-zh) | 安装、引擎选择、模型下载 |
| [服务器搭建](Server-Setup-zh) | 服务端原理、代理、内存建议 |
| [配置参考](Configuration-zh) | `voicecast.toml` 与 `models.json` 全键说明 |
| [访问控制](Access-Control-zh) | 总开关、白名单、权限钩子 |
| [性能与容量](Performance-zh) | 容量表、内存/CPU 画像 |
| [SVC 集成](Simple-Voice-Chat-Integration-zh) | Simple Voice Chat 共存模式 |
| [排障](Troubleshooting-zh) | 麦克风、红波形、下载问题 |

## For addon developers

```java
RecognizerRegistry.register("my-engine", MyEngine::new);
```

> **Planned feature** — the registration interface exists, but server-side engine selection/creation is not yet wired to the registry: only built-in engine ids are selectable today (tracked in the voicecast issue tracker).

The public API (`com.theo.voicecast.api`) never references `org.vosk` / `com.sun.jna` / `ai.onnxruntime` — compile against the published maven artifacts (`com.theo.voicecast:voicecast-common-1.20.1` etc.); the bundled implementation is provided at runtime.
