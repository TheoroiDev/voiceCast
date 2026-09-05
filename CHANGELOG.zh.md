# 更新日志 — VoiceCast

中文对照版；英文为主：[CHANGELOG.md](CHANGELOG.md)（两份保持同步，冲突以英文为准）。

## Unreleased（未发布）

### Features

- 可选麦克风采播降噪（`[client] noiseSuppression`，默认关）：流式 GTCRN 语音增强（sherpa-onnx，16 kHz 原生，约 523 KB 模型经目录自动下载），只作用于施法识别通路——其他玩家通过 Simple Voice Chat 听到的声音不受影响；降噪器任何失败自动降级为干净直通
- models.json v2 目录——模型目录即引擎列表（voicecast#42）：一个模型 = 一个引擎，模型名兼作引擎 id 与模型目录名；语言默认按声明顺序（第一个声明支持该语言的模型胜出）；选择接受模型名与二字语言码/常见语言名（`/voicecast engine en`、`zh`、`japanese`）；附属可通过 `properties.family` + 引擎族 SPI 声明自定义族；引擎选择界面与 `/voicecast engine list` 均由目录生成

### Changes

- breaking：models.json schema v2 将每模型的 `properties`（lang/type/选项）与 `source`（kind/urls/files）嵌套化，取消独立 `engines` 节——不做迁移（v0 政策，AGENTS §3）：非 v2 形态的文件按默认目录重写，旧引擎 id（`sherpa-zh-en`、`sherpa-sensevoice`、`ipa-phonemes` 及全部 vosk id）不再解析——请改用目录模型名（`sherpa-zipformer-bilingual-zh-en-int8`、`sherpa-sensevoice-small-int8`、`wav2vec2-espeak-ipa`）
- breaking：Vosk 移除，改用 sherpa-onnx 模型（voicecast#42）：`sherpa-zipformer-bilingual-zh-en-int8`——流式 zipformer 中英双语（默认）；`sherpa-sensevoice-small-int8`——离线 SenseVoice 覆盖 zh/yue/en/ja/ko，短句高精度档（int8 专版归档约 230 MB）；`sherpa-sensevoice-full`（1.1 GB 全量归档中的 fp32 权重）作为 A/B 对比条目一并保留；IPA 音素模型不变。模型经目录按需下载
- breaking：配置语义简化——`[client] engine` 留空 = 目录默认；`[server] defaultEngine` 接受模型名或语言码（留空 = 目录默认）；`[engines] allowed` 留空 = 允许目录中全部模型；配置不再有别名归一化或旧文件导入

### Bugfixes

- 进服时只要保存的引擎 id 超过 32 字符（v2 模型名长达 43+）就会抛 `String too big`：该异常打断了登录包处理器，连带杀死 fabric 客户端命令调度器的初始化——此后所有 `/voicecast` 客户端命令都报 `NullPointerException ... activeDispatcher is null`，且客户端命令从聊天补全中消失。引擎选择通道现可承载 256 字符，进服钩子也不会再破坏同级处理器
- 流式识别器在有法术热词时启动失败："Invalid OnlineRecognizerConfig: failed to create native OnlineRecognizer"——`cjkchar+bpe` 建模单元的热词编码需要模型的 `bpe.vocab`，此前从未传给 sherpa。现已接线（models.json 的 `bpe_vocab` 属性）；文件缺失时降级为无热词的开放词表解码，不再整体失败（voicecast#42）: failed to create native OnlineRecognizer"——`cjkchar+bpe` 建模单元的热词编码需要模型的 `bpe.vocab`，此前从未传给 sherpa。现已接线（models.json 的 `bpe_vocab` 属性）；文件缺失时降级为无热词的开放词表解码，不再整体失败（voicecast#42）
- sherpa 的配置 builder 默认 `debug=true`（倾倒内部状态）；识别器输出现已安静，除非开启开发调试（`gradlew runClient -PvoicecastVerbose=true` 或游戏内 `/voicecast verbose`，识别器（重）建时生效）
- 模型下载 HUD 在大归档的 999 MB 附近会"卡住"约 100 MB 的区间（GB 显示粒度太粗）；现在 10 GB 以内都用 MB 显示，计数可见递增
- 客户端专属命令（`/voicecast settings`、`verbose`、`debugwav`）可执行但不在聊天补全里：服务端 `/voicecast` 管理树遮蔽了同步的补全列表；现以 level-0 服务端 stub 把客户端子命令并入补全树（Fabric 与 Forge 皆然）
- sherpa 模型下载后现在会真正解压：下载器此前只解 `.zip`（vosk 的格式），sherpa 的 `tar.bz2` 归档被原样搁置，每次加载都报 "Downloaded model is missing expected files"；现同时支持 tar.bz2 / tgz / 裸 tar，并把归档内嵌的顶层目录提升为模型根目录（voicecast#42）
- 模型下载 HUD 对 sherpa 下载显示"正在下载 Vosk 模型"，现已改为"正在下载模型"

### Modding/API

- breaking：`Pronunciation` 新增按语言分桶的别名（`languages()`，两位码 en/zh/ja/ko）；平铺构造器保留（deprecated），其别名构成 legacy 桶、进入所有引擎 grammar；服务端按会话选中引擎的语言路由词表——引擎决定桶
- breaking：引擎 id 即目录模型名；deprecated 的 `ENGINE_*` 常量与 normalize 别名表已移除——请改用 `ModelConfig.resolveModel(...)`（精确名，其次按声明序的语言码）
- 新增：引擎族 SPI——`EngineFamilies.register(type, RecognizerFactory)`（`com.theo.voicecast.api.engine`），附属 mod 可注册自己的识别族，经 models.json 的 `properties.family` 选用；内置族：`sherpa-streaming`、`sherpa-sensevoice`、`ipa`（voicecast#19）

### Protocol

- `audio/select` 帧（客户端 → 服务端引擎选择）现可承载最长 256 字符的引擎 id——v2 模型名超出原 32 字符上限；客户端与服务端本就要求版本匹配

### Infrastructure

- 引擎类（IpaShared/IpaPhonemeRecognizer/VoskTextRecognizer 等）不再经 mod 主类路由日志——自持 slf4j logger，引擎可独立运行（voicecast fat jar），供 wizardreal ipa 回测工具使用
- 纯开发测试 mod 移出 gradle 依赖：release jar 预下载到工作区 `resources/devmods/<loader>/`，由 `manifest.txt` 驱动接线（fabric 硬链接进 run mods 目录；forge 作为文件依赖由 Loom 重映射；Carpet 的 Forge 移植仍受阻，voicecast#38）；语音模型事实源移至 `resources/models/`
- 开发模型预置改为 manifest 驱动：工作区 `resources/models/manifest.txt` 列出硬链进 run 目录的模型目录（每行一个，注释即禁用；manifest 缺失则全量预置）——vosk 模型随移除一并注释禁用
- vosk 依赖（compileOnly + fat-jar 打包）从构建中移除；tar 解压使用 Minecraft 运行时自带的 commons-compress（仅编译期依赖，不打进包）

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
