# 更新日志 — VoiceCast

中文对照版；英文为主：[CHANGELOG.md](CHANGELOG.md)（两份保持同步，冲突以英文为准）。

## Unreleased（未发布）

### Infrastructure

- 纯开发测试 mod 移出 gradle 依赖：release jar 预下载到工作区 `resources/devmods/<loader>/`，由 `manifest.txt` 驱动接线（fabric 硬链接进 run mods 目录；forge 作为文件依赖由 Loom 重映射；Carpet 的 Forge 移植仍受阻，voicecast#38）；语音模型事实源移至 `resources/models/`

## 0.3.2 — 2026-09-02

### Changes

- 引擎 id 统一为 `vosk-<语言>`：`vosk-en`、`vosk-cn`、`vosk-jp`、`vosk-kr`、`ipa-phonemes`；`vosk-text` 停用（普通英语 = `vosk-en`），完整引擎集开箱注册
- Simple Voice Chat 共存仅保留 share：实验性 `defer` 模式移除；配置 `svcCoexistence = "defer"` 回落 `share` 并一次性警告（voicecast#27）
- 移除无效服务端配置键 `[server] opusBitrate`（voicecast#3）

### Bugfixes

- 开发运行 `runServer` 与 `runClient` 分离运行目录（`run-server/`），Windows 下可并行
- 语音模型以硬链接自动预置进运行目录（零拷贝；删链接不动工作区副本）

### Modding/API

- breaking: 引擎 id 统一为 `vosk-<lang>`——引用 `vosk-text` 的配置/命令须改用 `vosk-en`

### Packaging

- IPA 模型仅发 q4 ONNX（float32 回退移除）；保留全平台 ONNX Runtime natives 供专用服务器
- `config/` gitignore 规则收窄到运行目录

### Infrastructure

- GitHub Actions 构建+测试流水线（tag/PR 触发，foojay toolchain）；新 issue 自动加入 Be a Real Wizard 项目；issue 模板（bug/feature/config）
- Wiki 拆分为 VoiceCast 专属双语页面

## 0.3.1 — 2026-09-01

### Features

- Simple Voice Chat 共存（M7b）：VoiceCast 始终共享麦克风设备；SVC 最近 300 ms 内有传输时输出一条限速 info 提示（绝不阻塞或静音 SVC）

### Changes

- 清理无效配置字段（M7b W4）

### Modding/API

- SVC 插件按 loader 原生机制注册（Fabric entrypoint key `voicechat` / Forge `@ForgeVoicechatPlugin` 注解扫描）——不走 ServiceLoader；SVC 不在场时插件类永不实例化

### Packaging

- voicechat-api 2.6.20 为 compileOnly 永不打包；`verifyFatJarPolicy` 守护打包规则（module-info 剥离、JNA 政策、Concentus 存在性）

### Infrastructure

- 仓库拆分基线收尾：GitHub issue 模板、add-to-project workflow

## 0.3.0 — 2026-09-01（工作区拆分基线）

### Features

- 离线语音施法基础：按住说话（PTT）采集、能量 VAD、流式 Vosk 识别（en/cn/ja/ko 模型）、wav2vec2-espeak IPA 音素引擎、法术匹配、带麦克风电平表的屏上转写 HUD
- 服务端识别：客户端只推 Opus 编码音频、零模型下载（q4 IPA 模型 ~230 MB 存服务端）
- 模型管理器：SHA 校验下载 + hf-mirror 回退
- 持久化客户端/服务端配置（引擎选择、HUD 锚点、VAD 阈值、详细日志）

### Modding/API

- 稳定 addon SPI `com.theo.voicecast.api`：`SpeechRecognizer`、`RecognizerRegistry`、`Pronunciation`、`VoiceCastEvents`（partial/final/state/audio-level 事件）；公共 API 不含 vosk/JNA/ORT 类型

### Protocol

- `voicecast:audio` 通道：Opus（Concentus）帧流 c2s，state/transcript s2c；每玩家服务端识别会话

### Packaging

- Architectury 双加载器（Fabric + Forge）；JNA 永不 relocate/shade；Vosk/ORT natives 打包 win/linux/macos
