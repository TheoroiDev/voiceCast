# [English](Configuration) | [中文](Configuration-zh)

# 配置参考

> [← 首页](Home-zh) · 上一篇：[服务器搭建](Server-Setup-zh) · 下一篇：[访问控制](Access-Control-zh)

VoiceCast 使用**一个共享配置文件**（客户端/服务器各读自己的节）：

- `<游戏目录>/config/voicecast/voicecast.toml` — 开关、引擎、白名单
- `<游戏目录>/config/voicecast/models.json` — 模型目录（v2）与镜像
- 模型实体文件：`config/voicecast/models/<模型名>/`

两个文件在首次加载时自动创建并写回（带版本号，缺失键自动补默认值）。修改后重启服务器生效。

## voicecast.toml

```toml
version = 1

[server]
defaultEngine = ""            # 模型名或语言码；留空 = 目录默认（第一个声明支持 zh 的模型）
autoDownload = true           # 允许服务器自动下载模型
maxFramesPerSecond = 15       # 每会话音频帧速率上限（防滥用）
enabled = true                # 总开关：false 时任何玩家都无法使用语音

[engines]
allowed = []                  # 引擎 id；留空 = 允许目录中全部模型

[players]
whitelist = []                # UUID 字符串数组；空 = 所有人可用

[compat]
svcCoexistence = "share"      # Simple Voice Chat 共存（客户端本地设置）

[client]                      # ← 玩家本地设置
engine = ""                   # 模型名 / 语言码；留空 = 目录默认
noiseSuppression = false      # 识别通路麦克风采播降噪（GTCRN）；见下表说明
```

| 键 | 说明 |
|---|---|
| `[server] defaultEngine` | 启动时预热的引擎。接受模型名或两位语言码；留空/无法解析 = 目录默认（第一个声明支持 zh 的模型） |
| `[server] autoDownload` | `false` 时服务器不下载任何模型，缺失即报 `NO_MODEL`（需手动放置） |
| `[server] maxFramesPerSecond` | 单个玩家每秒最多发送的音频帧数，超出部分丢弃（防刷包） |
| `[server] enabled` | **总开关**。`false`：不预热模型，所有音频帧静默丢弃，玩家收到一次性"已禁用"提示 |
| `[engines] allowed` | 可选引擎白名单。**留空 = 允许目录中全部模型**；非空则仅列出的 id 可用 |
| `[players] whitelist` | UUID 数组（非法 UUID 跳过并告警）。**空 = 所有人可用**；非空则仅名单内玩家可推流。判定顺序见[访问控制](Access-Control-zh) |
| `[compat] svcCoexistence` | Simple Voice Chat 共存模式（**客户端本地设置**：每个玩家各自的配置，服务端不读取也不同步）— 见 [SVC 集成](Simple-Voice-Chat-Integration-zh) |
| `[client] engine` | 玩家本地引擎偏好：模型名、语言码（`en`/`zh`/`ja`/`ko`…）或留空取目录默认。可通过 `/voicecast engine <参数>` 与 `/voicecast settings` 调整 |
| `[client] noiseSuppression` | 麦克风采播降噪（sherpa-onnx GTCRN），**只影响施法识别通路**——默认关。其他玩家听到的声音走 Simple Voice Chat 的独立采集，请使用 SVC 自带的降噪 |

> 引擎 id 即 `models.json` 中的**模型名**（一个模型 = 一个引擎）。默认目录：`qwen3-asr-0.6b-int8`（离线整句，en/zh/ja/ko/yue/de/fr/es/ru，默认）、`zipa-ipa`（IPA 音素）、`gtcrn-simple-denoiser`（辅助降噪）。每个模型服务端只下载一次并全服共享。

## models.json（模型目录，v2）

首次自动生成默认目录，之后**完全由用户所有**（按解析结果原样写回；无迁移——非 v2 形态的文件会按默认重写）。一个模型 = 一个引擎：模型名兼作引擎 id 与模型目录名。

```json
{
  "version": 2,
  "$schema": "docs/schemas/voicecast-models-v2.schema.json",
  "models": {
    "qwen3-asr-0.6b-int8": {
      "properties": {
        "lang": ["en","zh","ja","ko","yue","de","fr","es","ru"], "type": "offline",
        "family": "sherpa-qwen3",
        "conv_frontend": "conv_frontend.onnx", "encoder": "encoder.int8.onnx",
        "decoder": "decoder.int8.onnx", "tokenizer": "tokenizer",
        "num_threads": "8", "max_total_len": "600", "max_new_tokens": "256"
      },
      "source": { "kind": "sherpa-archive", "size_bytes": 878702423,
                  "urls": ["https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"] }
    },
    "zipa-ipa": {
      "properties": { "type": "ipa" },
      "source": { "kind": "loose-files", "files": [
        { "name": "model.int8.onnx", "minBytes": 62914560, "sha256": "e79c5ec3...", "urls": ["https://huggingface.co/anyspeech/zipa-small-crctc-ns-no-diacritics-700k/resolve/main/model.int8.onnx", "https://hf-mirror.com/anyspeech/..."] },
        { "name": "tokens.txt", "sha256": "f8e042a0...", "urls": [".../tokens.txt"] } ] }
    },
    "gtcrn-simple-denoiser": {
      "properties": { "type": "denoiser" },
      "source": { "kind": "loose-files", "files": [ { "name": "gtcrn_simple.onnx", "minBytes": 400000, "urls": ["..."] } ],
                  "license": { "name": "MIT", "url": "<上游许可链接>" } }
    }
  },
  "mirrorProbe": { "enabled": true, "probeBytes": 262144, "timeoutMs": 5000, "minFileSizeBytes": 8388608 }
}
```

要点：

- **一个模型 = 一个引擎**：模型名兼作引擎 id 与模型目录名；没有独立的 `engines` 节；
- **语言默认按声明顺序**：第一个 `lang` 包含某语言的模型即为该语言默认（`/voicecast engine en` 选中它）；
- **`properties.type`**：`offline`（整句 ASR）、`ipa`（音素）、`denoiser`（辅助增强模型——走同一下载管线但绝不作为引擎列出/选择）；`properties.family` 决定引擎族——仅 ipa 类有默认推导，其余（如 `sherpa-qwen3`）必须显式声明；
- **`source.license`**（voiceCast#51）：为每个模型声明许可（`name` + 上游 `url`）——同意门据此生成 `/voicecast licenses` 清单；没有 license 块的模型显示 "unspecified"，下载前同样需要接受；
- **多镜像测速**：`source.urls` 配多个地址时并发 Range-GET 探测吞吐，**最快者先下载**；小文件跳过探测；
- **自托管模型**：把 `urls` 换成你自己的 HTTP 地址即可（内网镜像、对象存储都行）——那是你自己的分发行为，本项目本身不转存任何模型权重；
- 手动放置：`autoDownload=false` 时把解压后的文件放到 `config/voicecast/models/<模型名>/`（sherpa 归档需 tokens 等价物与 `.onnx` 文件位于模型根目录——`tokens.txt`，Qwen3-ASR 则为 `tokenizer/` 目录）。

## 诊断

诊断可用 JVM 参数 `-Dvoicecast.verbose=true`（或 `-PvoicecastVerbose=true` 启动）输出识别管线日志，或使用 `/voicecast status` / `/voicecast engine list`。

> [← 首页](Home-zh) · 上一篇：[服务器搭建](Server-Setup-zh) · 下一篇：[访问控制](Access-Control-zh)
