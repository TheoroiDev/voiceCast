# [English](Home) | [中文](Home-zh)

# VoiceCast · 中文文档

可复用的 Minecraft 1.20.1 **离线语音识别库模组**（Fabric + Forge），是 [Be a Real Wizard](https://github.com/TheoroiDev/wizardReal) 的语音引擎，任何 addon 模组都可嵌入。

> 识别**完全在服务器端**进行——玩家本地不下载模型、不跑推理。

## 核心特性

- **双引擎**：Vosk 词语识别（~40 MB）/ wav2vec2-espeak IPA 音素识别（~150 MB q4）
- **模型托管**：自动下载 + sha256 校验，支持完全自托管——Vosk 模型默认仅从 alphacephei.com 下载，IPA（wav2vec2）模型走 hf-mirror.com 镜像，也可在 `models.json` 自行追加镜像
- **服务器访问控制**：`[server] enabled` 总开关 + `[players]` UUID 白名单 + 可插拔 `AccessCheck` 钩子
- **Simple Voice Chat 集成**：共享/暂缓（share/defer）两种共存模式 — 见 [SVC 集成](Simple-Voice-Chat-Integration-zh)
- **引擎 SPI**（计划中）：`RecognizerRegistry.register(...)` 接口已就绪，但服务端引擎选择/创建尚未接入注册表——当前仅内置引擎 id 可选（见 voicecast 仓库 issue）

## 文档（中文）

| 页面 | 内容 |
|---|---|
| [入门](Getting-Started-zh) | 安装、引擎选择、模型下载、首次识别 |
| [服务器搭建](Server-Setup-zh) | 服务端原理、代理、内存建议 |
| [配置参考](Configuration-zh) | `voicecast.toml` 与 `models.json` 全键说明 |
| [访问控制](Access-Control-zh) | 总开关、白名单、权限钩子 |
| [性能与容量](Performance-zh) | 容量表、内存/CPU 画像 |
| [SVC 集成](Simple-Voice-Chat-Integration-zh) | Simple Voice Chat 共存模式（share/defer） |
| [排障](Troubleshooting-zh) | 麦克风、红波形、下载、引擎切换 |

## 文档（English）

| Page | Contents |
|---|---|
| [Home](Home) | Overview & quick facts |
| [Getting Started](Getting-Started) | Install, engine choice, model download, first recognition |
| [Server Setup](Server-Setup) | How server-side recognition works, proxies, memory sizing |
| [Configuration](Configuration) | Full `voicecast.toml` + `models.json` reference |
| [Access Control](Access-Control) | Master switch, UUID whitelist, permission hook |
| [Performance](Performance) | Capacity cheat sheet, memory/CPU profile |
| [Simple Voice Chat Integration](Simple-Voice-Chat-Integration) | Coexistence modes |
| [Troubleshooting](Troubleshooting) | Microphone, red waveform, downloads, engine switching |

## 面向 addon 开发者

```java
RecognizerRegistry.register("my-engine", MyEngine::new);
```

> **计划中特性**——注册接口已就绪，但服务端的引擎选择/创建尚未接入注册表：当前仅内置引擎 id 可选（见 voicecast 仓库 issue 跟踪）。

公共 API（`com.theo.voicecast.api`）不引用 `org.vosk` / `com.sun.jna` / `ai.onnxruntime`——请针对发布的 maven 产物编译（`com.theo.voicecast:voicecast-common-1.20.1` 等），捆绑实现在运行时提供。
