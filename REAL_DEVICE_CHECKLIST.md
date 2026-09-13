# voicecast 真机测试 Checklist（sherpa 0.4.0）

> **如何使用**：① 执行环境——JDK 21 跑 Gradle，`gradlew :voicecast-<fabric|forge>:runClient / runServer`；run 目录自动分离（server 跑 `run-server/`）、runServer 自动写 eula + 关 online-mode、runClient 用户名固定 `dev`、模型按 `resources/models/manifest.txt` 硬链接预置（当前启用：wav2vec2-espeak-ipa、sherpa-zipformer-bilingual-zh-en-int8、sherpa-zipformer-small-bilingual-zh-en-int8、sherpa-sensevoice-small-int8）。② 判定记录回写本文件末"判定记录"表；日志与截图写 `voicecast/test/logs/`。③ 双终端流程/排障/日志关键词以 `docs/testing/README.md` 为准，本清单不重复。
> 分级：P0 = 挡 TRL 8 闸门（真机双端联测）；P1 = 发布前必过；P2 = 质量加固。全部条目当前均未在 0.4.0 时代真机执行过。

## 0. 环境前置

- ☐ `resources/models/manifest.txt` 列出的 4 个模型目录硬链接进 `voicecast\voicecast\<loader>\run\config\voicecast\models\`（启动时自动，查目录存在即可）。
- ☐ JDK 21 环境变量已设（`$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"`）。
- ☐ 麦克风权限与默认输入设备可用（排障见 `docs/testing/README.md` §5）。

## 1. P0 — TRL 8 闸门

### VC-P0-1 · 服务端冒烟：catalog 模型预置 + 引擎就绪（双 loader）

- 场景：sherpa 0.4.0 换血后服务端可启动、默认引擎按 catalog 声明序就绪。
- 前置：环境前置 3 项全过。
- 步骤：① `.\gradlew :voicecast-fabric:runServer`（90 秒后 stdin 发 `stop`）② `:voicecast-forge:runServer` 同法。
- 判定：日志 `Done (…s)!` → `Server voice engine ready: sherpa-zipformer-bilingual-zh-en-int8`（默认 = 第一个声明 zh 的模型）→ `Stopping the server` 优雅关停；全程无 ERROR、无 `Failed to remap mods`。
- 证据：`voicecast/test/logs/smoke-<loader>-<日期>.log`。
- 状态：☐（上轮已过 0.3.x vosk 时代：2026-09-01，需在 sherpa 0.4.0 复测）

### VC-P0-2 · 双终端 E2E：standalone 识别链路

- 场景：C2S 音频流 → 服务端识别全链路（voicecast 自身产品能力，无 wizardreal）。
- 前置：VC-P0-1 过；终端 1 runServer + 终端 2 runClient（fabric），客户端连接 `localhost`。
- 步骤：① 进世界按住说话键，念中/英文短语各 5 句 ② 加 `-PvoicecastVerbose=true` 重跑看识别管线 ③ forge 双终端同型复验。
- 判定：客户端波形绿色随音量起伏；verbose 日志出现识别文本且与所念内容一致（≥4/5 句）；forge 侧同判。
- 证据：`voicecast/test/logs/e2e-<loader>-<日期>/`（双侧 latest.log）。
- 状态：☐

### VC-P0-3 · 引擎选择全链路（models.json v2 = 引擎列表）

- 场景：catalog 驱动的引擎选择（模型名/语言码/界面/持久化）与 256 字符选择帧回归。
- 前置：VC-P0-2 环境就绪。
- 步骤：① `/voicecast engine list` ② `/voicecast engine zh`、`en`、`japanese` 各一次 ③ `/voicecast settings` 界面选择长模型名引擎 ④ 选定后退出世界重进 ⑤ 故意保存 >32 字符模型名后重登。
- 判定：① 只列 catalog 模型名（无 `vosk-*`/`sherpa-zh-en`/`ipa-phonemes` 旧 id）；② 语言码按声明序选中对应模型；③ 界面可选可保存；④ 重登保持所选；⑤ 客户端命令不再从补全消失（无 `activeDispatcher is null` NPE——`String too big` 回归项）。
- 证据：settings 界面截图 + `voicecast/test/logs/engine-select-<日期>.log`。
- 状态：☐

### VC-P0-4 · 热词链路：bpe.vocab 接线

- 场景：streaming 引擎带 spell 热词启动不崩（`cjkchar+bpe` 编码依赖 bpe.vocab）。
- 前置：models.json 的 `sherpa-zipformer-bilingual-zh-en-int8` 含 `bpe_vocab` 属性且文件在模型目录内；或装 wizardreal 注入词表。
- 步骤：热词生效状态下启动 streaming 会话并说话。
- 判定：无 `Invalid OnlineRecognizerConfig: failed to create native OnlineRecognizer`；bpe.vocab 缺失时降级为无热词开放词表解码（WARN 而非失败）。
- 证据：`voicecast/test/logs/hotword-<日期>.log`。
- 状态：☐

## 2. P1 — 发布前必过

### VC-P1-1 · 双引擎 A/B：zipformer streaming vs sensevoice offline

- 步骤：同一批短句（触发词级，zh/en 各 10 条）分别在两引擎下识别，记录命中率。
- 判定：sensevoice（offline 短句高准确）命中 ≥ zipformer（streaming）；两者均 ≥80%。
- 证据：`voicecast/test/logs/ab-engines-<日期>.log` + 命中率记录。
- 状态：☐

### VC-P1-2 · 降噪开关（GTCRN，默认关）

- 步骤：① 默认关 → 念 10 句记录基线 ② `[client] noiseSuppression = true` → 同语料复测 ③ 人为制造噪音环境再测 ④ 同时开 Simple Voice Chat 验证共享麦克风。
- 判定：开启后识别可用性不降；降噪器失败时降级为干净直通（不崩、有一次 WARN）；SVC 侧对方听到的声音不受本开关影响（share 模式提示行正常）。
- 证据：`voicecast/test/logs/denoise-<日期>.log`。
- 状态：☐

### VC-P1-3 · 模型按需下载：tar.bz2 解包 + HUD

- 步骤：① 删除 `sherpa-sensevoice-small-int8` 模型目录 → 启动选择该引擎 ② 观察下载 HUD ③ 等待解包与就绪。
- 判定：HUD 文案为 "Downloading model"（非 Vosk）；MB 计数走动（不长期卡 999MB）；tar.bz2 解包成功（嵌套顶层目录 hoist 进模型根）；引擎最终就绪。
- 证据：下载过程截图 + `voicecast/test/logs/download-<日期>.log`。
- 状态：☐

### VC-P1-4 · 配置 v0 硬切语义

- 步骤：① 手写 v1 形状 models.json（带 `engines` 段）→ 启动 ② 清空 `[client] engine` 启动 ③ `[engines] allowed` 留空启动。
- 判定：① 文件被重写为默认 catalog（无迁移日志、无 LEGACY 残留）；② 空 = catalog 默认（第一个声明 zh 的模型）；③ 空 = 全部 catalog 模型可选。
- 证据：重写后的 `models.json` 拷贝 + `voicecast/test/logs/config-v2-<日期>.log`。
- 状态：☐

### VC-P1-5 · forge 客户端主干复验

- 步骤：forge runClient + runServer 双终端，走 VC-P0-2 与 VC-P0-3 的主干步骤。
- 判定：与 fabric 同判。
- 证据：`voicecast/test/logs/e2e-forge-<日期>/`。
- 状态：☐

## 3. P2 — 质量加固

### VC-P2-1 · `sherpa-sensevoice-full`（fp32）与 int8 精度对照

- 步骤：预下载 full 模型后与 int8 跑同语料（复用 VC-P1-1 的短句批）。
- 判定：命中率差异记录在案（int8 损失可量化）；两模型均可加载。
- 证据：`voicecast/test/logs/ab-sensevoice-full-<日期>.log`。状态：☐

### VC-P2-2 · 诊断开关

- 步骤：fabric + forge 各验：`-PvoicecastVerbose=true`、`/voicecast verbose`、`/voicecast debugwav`。
- 判定：识别管线日志出现；桌面 wav 文件生成；开关即时生效。证据：`voicecast/test/logs/diag-<日期>.log`。状态：☐

### VC-P2-3 · 服务端 admin 命令组

- 步骤：`/voicecast status`、`engine default <model>`（重启验证持久化）、`enabled false`（全员禁用广播）、`whitelist add/remove`（白名单外音频被拒 + 一次性提示）、改 toml 后 `reload` 热生效。
- 判定：各命令行为与 `docs/testing/README.md` §7 口径一致。证据：`voicecast/test/logs/admin-<日期>.log`。状态：☐

### VC-P2-4 · 加载耗时基线

- 步骤：记录 4 模型各自首载（冷）与重启（热）秒数。
- 判定：数字落表即可（供后续回归对照，不设阈值）。证据：本文件判定记录表。状态：☐

## 4. 判定记录

| 日期 | 项 | 结果（过/挂 + 关键数字） | 日志位置 |
|---|---|---|---|
| | | | |
