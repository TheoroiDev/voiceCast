# [English](Server-Setup) | [中文](Server-Setup-zh)

# 服务器搭建

> [← 首页](Home-zh) · 上一篇：[入门](Getting-Started-zh) · 下一篇：[配置参考](Configuration-zh)

## 工作原理（先读这个）

识别**完全在服务器端**：

- 客户端只采集麦克风 → Opus 压缩（约 **3 KB/s** 每个正在说话的玩家）→ 通过**原版 Minecraft 连接**发送；
- 服务器运行 ONNX 推理（Qwen3-ASR 整句 / ZIPA 音素模型）并施放法术；
- **不需要开放额外端口**、不需要额外防火墙规则；玩家本地不下载模型、不跑推理。

## 安装

把 `voicecast-<loader>-*.jar`（或 fabric 版）放入 `mods/` 目录。客户端与服务端都必须。

## 模型下载

- 服务器启动时**预热默认引擎**（`[server] defaultEngine`，默认即目录默认 Qwen3-ASR，~880 MB），玩家首次选用其他引擎时按需下载并**全服共享**；
- 下载走 HTTPS + 校验和/大小校验。Qwen3-ASR 模型来自 **sherpa-onnx 官方 GitHub release**；**IPA（ZIPA）** 模型走 HuggingFace 并带 hf-mirror.com 镜像——也可在 `models.json` 自行追加镜像（多个 `urls` 会并发测速、最快者优先）；
- **无外网/下载慢**的服务器：
  - JVM 代理参数：`-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>`（下载器也会探测 `HTTPS_PROXY` 环境变量）；
  - 或设 `[server] autoDownload = false` 并**手动放置**模型到 `config/voicecast/models/<模型id>/`（sherpa 归档需 `.onnx` 文件位于模型根目录；ZIPA 目录需 `model.int8.onnx` + `tokens.txt`）；
- 模型目录与校验清单见[配置参考](Configuration-zh)。

## 内存与硬件建议

| 规模 | 建议 |
|---|---|
| ≤20 在线（3–5 人同时说话） | 4 核 / 8 GB |
| ~50 在线（~10 人说话） | 16 核 / 16 GB |
| 100 在线 | 32 核 / 32 GB，且需要先做识别器闲置回收（见[性能](Performance-zh)） |

共享模型层内存：Qwen3-ASR 常驻 ~1.5–2.8 GB（每热词集一个共享实例，lab 实测峰值 RSS）；ZIPA 含 ONNX 运行时 ~0.5 GB。每个说话玩家的会话另占 30–80 MB。详见[性能](Performance-zh)。

## 验证

1. 启动日志出现 `Server voice engine ready: <engine>`（默认 `qwen3-asr-0.6b-int8`；其余引擎在玩家首次选择时懒加载）；
2. 客户端进入世界推流后服务器日志出现识别活动；
3. `/voicecast engine` 可查看玩家当前引擎。

## 注意事项

- **专用服务器安全**：voicecast 服务端代码不引用任何客户端/LWJGL 类；sherpa-onnx/ONNX 的全平台 natives 已内置（Linux x64/arm、macOS 可用）。

> [← 首页](Home-zh) · 上一篇：[入门](Getting-Started-zh) · 下一篇：[配置参考](Configuration-zh)