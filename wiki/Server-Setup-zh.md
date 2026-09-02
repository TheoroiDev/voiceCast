# [English](Server-Setup) | [中文](Server-Setup-zh)

# 服务器搭建

> [← 首页](Home-zh) · 下一篇：[配置参考](Configuration-zh)

## 工作原理（先读这个）

识别**完全在服务器端**：

- 客户端只采集麦克风 → Opus 压缩（约 **3 KB/s** 每个正在说话的玩家）→ 通过**原版 Minecraft 连接**发送；
- 服务器运行 Vosk / ONNX 推理并施放法术；
- **不需要开放额外端口**、不需要额外防火墙规则；玩家本地不下载模型、不跑推理。

## 安装

把 `voicecast-forge-*.jar`（或 fabric 版）放入服务端 `mods/`。客户端与服务端都装。

## 模型下载

- 服务器启动时**预热默认引擎**（`[server] defaultEngine`，默认 Vosk 英文 ~40 MB），玩家首次选用其他引擎时按需下载并**全服共享**；
- 下载走 HTTPS + sha256 校验。Vosk 模型默认**仅从 alphacephei.com** 下载；**IPA（wav2vec2）** 模型走 hf-mirror.com 镜像——也可在 `models.json` 为 Vosk 模型自行追加镜像（多个 `urls` 会并发测速、最快者优先）；
- **无外网/下载慢**的服务器：
  - JVM 代理参数：`-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>`（下载器也会探测 `HTTPS_PROXY` 环境变量）；
  - 或设 `[server] autoDownload = false` 并**手动放置**模型到 `config/voicecast/models/<模型id>/`（Vosk 解压后需含 `am/ conf/ graph/` 子目录）；
- 模型目录与校验清单见[配置参考](Configuration-zh)。

## 内存与硬件建议

| 规模 | 建议 |
|---|---|
| ≤20 在线（3–5 人同时说话） | 4 核 / 8 GB |
| ~50 在线（~10 人说话） | 16 核 / 16 GB |
| 100 在线 | 32 核 / 32 GB，且需要先做识别器闲置回收（见[性能](Performance-zh)） |

共享模型层内存：Vosk 英文 ~150–250 MB；全部 4 种 Vosk 语言 ~0.8–1 GB；IPA（q4 权重 + ONNX 运行时）~400–600 MB。每个说话玩家的会话另占 30–80 MB。详见[性能](Performance-zh)。

## 验证

1. 启动日志出现 `Server voice engine ready: <engine>`（默认 `vosk-text`；其余语种在玩家首次选择时懒加载）；
2. 客户端进入世界推流后服务器日志出现识别活动；
3. `/voicecast engine` 可查看玩家当前引擎。

## 注意事项

- **专用服务器安全**：voicecast 服务端代码不引用任何客户端/LWJGL 类；ONNX/Vosk 的全平台 natives 已内置（Linux x64/arm、macOS 可用）；
- 升级模组：配置 schema 自动迁移（`config/voicecast/voicecast.toml` 带版本号），旧的 `server.properties`/`client.properties` 会被一次性导入并删除。
