# [English](Configuration) | [中文](Configuration-zh)

# 配置参考

> [← 首页](Home-zh) · 上一篇：[服务器搭建](Server-Setup-zh) · 下一篇：[访问控制](Access-Control-zh)

VoiceCast 使用**一个共享配置文件**（客户端/服务器各读自己的节）：

- `<游戏目录>/config/voicecast/voicecast.toml` — 开关、引擎、白名单
- `<游戏目录>/config/voicecast/models.json` — 模型目录与镜像
- 模型实体文件：`config/voicecast/models/<模型id>/`

文件在首次加载时自动创建并写回（带版本号，缺失键自动补默认值）；旧版 `server.properties` / `client.properties` 会一次性导入后删除。修改后重启服务器生效。

## voicecast.toml

```toml
version = 1

[server]
defaultEngine = "vosk-text"   # vosk-text | vosk-en | vosk-cn | vosk-jp | vosk-kr | ipa-phonemes | noop
autoDownload = true           # 允许服务器自动下载模型
maxFramesPerSecond = 15       # 每会话音频帧速率上限（防滥用）
enabled = true                # 总开关：false 时任何玩家都无法使用语音

[engines]
allowed = ["vosk-text", "vosk-en", "vosk-cn", "vosk-jp", "vosk-kr", "ipa-phonemes"]

[players]
whitelist = []                # UUID 字符串数组；空 = 所有人可用

[compat]
svcCoexistence = "share"      # share | defer（Simple Voice Chat 共存模式）

[client]                      # ← 玩家本地设置，服主一般不用动
engine = "vosk-text"
```

| 键 | 说明 |
|---|---|
| `[server] defaultEngine` | 启动时预热的引擎；玩家未选择时也用它 |
| `[server] autoDownload` | `false` 时服务器不下载任何模型，缺失即报 `NO_MODEL`（需手动放置） |
| `[server] maxFramesPerSecond` | 单个玩家每秒最多发送的音频帧数，超出部分丢弃（防刷包） |
| `[server] enabled` | **总开关**。`false`：不预热模型，所有音频帧静默丢弃，玩家收到一次性"已禁用"提示 |
| `[engines] allowed` | 玩家可选引擎白名单（`audio/select` 被拒会提示 "engine not allowed"）。用于阻止玩家触发大模型下载 |
| `[players] whitelist` | UUID 数组（非法 UUID 跳过并告警）。**空 = 所有人可用**；非空则仅名单内玩家可推流。判定顺序见[访问控制](Access-Control-zh) |
| `[compat] svcCoexistence` | Simple Voice Chat 共存模式（**客户端本地设置**：每个玩家各自的配置，服务端不读取也不同步）— 见 [SVC 集成](Simple-Voice-Chat-Integration-zh) |
| `[client] engine` | 玩家本地引擎偏好。合法值：`vosk-text` / `vosk-en` / `vosk-cn` / `vosk-jp` / `vosk-kr` / `ipa-phonemes`（命令别名 vosk/en/zh/ja/ko/ipa 等会归一化） |

> 原 `[server] opusBitrate` 键已**移除**：Opus 编码器运行在客户端，服务端键无法触达（那需要新增同步通道）。带宽约 3 KB/s 每个说话玩家（见 [Performance](Performance-zh.md)）。旧配置文件里遗留的 `opusBitrate` 会被忽略。

> CJK 引擎：`vosk-cn` / `vosk-jp` / `vosk-kr` 已完整注册（母语 Vosk 识别）。每个被选中的语种会下载并常驻一份自己的共享模型（磁盘约 40–90 MB，加载后内存约 150–250 MB）——懒加载，只有玩家实际使用的语种才付费。日语注意：ja 模型输出假名/汉字文本，罗马音拼写的咒语别名在文本路径可能不命中；建议用假名别名或改用 `ipa-phonemes` 引擎。

## models.json（模型目录）

自动生成、支持**用户覆盖合并**（按键合并，缺的补默认值）。结构：

```json
{
  "version": 1,
  "models": {
    "vosk-model-small-en-us-0.15": {
      "kind": "vosk-archive",
      "sizeBytes": 41205931,
      "sha256": "30f26242c4eb...",
      "urls": ["https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"]
    },
    "wav2vec2-espeak-ipa": {
      "kind": "loose-files",
      "files": {
        "vocab.json":    { "minBytes": 1,       "urls": ["https://hf-mirror.com/...", "https://huggingface.co/..."] },
        "model_q4.onnx": { "minBytes": 150000000, "urls": [".../model_q4.onnx", ".../model_q4.onnx"] }
      }
    }
  },
  "engines": {
    "vosk-text":   { "model": "vosk-model-small-en-us-0.15" },
    "vosk-en":     { "model": "vosk-model-small-en-us-0.15" },
    "vosk-cn":     { "model": "vosk-model-small-cn-0.22" },
    "vosk-jp":     { "model": "vosk-model-small-ja-0.22" },
    "vosk-kr":     { "model": "vosk-model-small-ko-0.22" },
    "ipa-phonemes": { "model": "wav2vec2-espeak-ipa" }
  },
  "mirrorProbe": { "enabled": true, "probeBytes": 262144, "timeoutMs": 5000, "minFileSizeBytes": 8388608 }
}
```

要点：

- **多镜像测速**：每个文件配多个 URL 时，服务器会并发 Range-GET 探测各镜像吞吐，**最快者先下载**、其余作回退；小于 8 MB 的文件跳过探测；
- **自托管模型**：把 `urls` 换成你自己的 HTTP 地址即可（内网镜像、对象存储都行）；
- **IPA 模型**仅提供 q4 量化版 `model_q4.onnx`（约 150 MB）——没有 f32 兜底；
- 手动放置：`autoDownload=false` 时把文件放到 `config/voicecast/models/<模型id>/`，Vosk 需解压后含 `am/ conf/ graph/`。

## 玩家可调项

玩家唯一的文件配置是 `[client] engine`；其余（HUD 开关、静音断句阈值等）为代码内常量。诊断可用 JVM 参数 `-Dvoicecast.verbose=true` 输出识别管线日志。
