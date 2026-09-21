# [English](Getting-Started) | [中文](Getting-Started-zh)

# 入门（VoiceCast）

> [← 首页](Home-zh) · 下一篇：[服务器搭建](Server-Setup-zh)

VoiceCast 是**库模组**：识别在**服务器端**进行，客户端只推流音频。它驱动 [Be a Real Wizard](https://github.com/TheoroiDev/wizardReal)，也可以作为引擎宿主被任何 addon 模组使用。

## 安装

| 平台 | Jar | 前置 |
|---|---|---|
| Fabric | `voicecast-fabric-*.jar` | Fabric Loader、Fabric API、Architectury API |
| Forge | `voicecast-forge-*.jar` | Forge 47.x、Architectury API |

客户端与服务器都装。VoiceCast 把 Simple Voice Chat / Plasmo Voice 声明为**可选**——与它们共存（见 [SVC 集成](Simple-Voice-Chat-Integration-zh)）。

## 选择识别引擎

通过 Mod Menu / 模组列表的**配置按钮**，或 `/voicecast settings` 命令：

| 引擎 | 大小 | 适合 |
|---|---|---|
| **整句识别** `qwen3-asr-0.6b-int8` | ~880 MB | 自由说出的触发词与咒语吟唱，覆盖 en/zh/ja/ko/yue/de/fr/es/ru（默认） |
| **音素识别（IPA）** `zipa-ipa` | ~70 MB | 按发音念拉丁/中/日文咒语（音素匹配） |

模型只在**首次选用时下载一次**到 `config/voicecast/models/`（sha256 校验）。Qwen3-ASR 模型来自 **sherpa-onnx 官方 GitHub release**；**IPA（ZIPA）** 模型来自 HuggingFace 并带 hf-mirror.com 镜像——也可在 `models.json` 自行追加镜像。随时切换：

```
/voicecast engine en      # 整句识别（Qwen3-ASR，默认）
/voicecast engine zh      # 同一引擎的语言码路由
/voicecast engine ipa     # IPA 音素识别（ZIPA）
```

## 首次识别

1. 进世界（玩法模组驱动 PTT 时波形出现——基础组合：WizardReal 持杖右键）；
2. 说话——绿色波形随音量起伏，灰色斜体为实时识别，白色引号为最终结果；
3. 波形上方的**金色/红色状态行**以你的游戏语言报告模型下载与错误。

## 模型托管（无外网服务器）

- JVM 代理参数：`-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>`；
- 自托管：编辑 `config/voicecast/models.json`，把 `urls` 指向自己的 HTTP 服务；
- 完全离线：`[server] autoDownload = false` + 手动放置模型——详见[配置参考](Configuration-zh)。

## 下一步

- 服主必读：[服务器搭建](Server-Setup-zh)、[访问控制](Access-Control-zh)。
- 性能规划：[性能与容量](Performance-zh)。
- 问题排查：[排障](Troubleshooting-zh)。

> [← 首页](Home-zh) · 下一篇：[服务器搭建](Server-Setup-zh)