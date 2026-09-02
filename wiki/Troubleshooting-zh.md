# [English](Troubleshooting) | [中文](Troubleshooting-zh)

# 排障（VoiceCast）

> [← 首页](Home-zh)

## 没有波形 HUD

1. 波形由玩法模组驱动 PTT 时显示（基础组合：**持杖 + 右键**）；
2. 必须**在世界内**；
3. 两个模组都装了（voicecast + 玩法模组）。

## 波形是红色噪点

红色 = 模型尚未就绪。看波形上方的**状态行**：

- **金色**"正在下载/准备模型"：首次下载，耐心等待（Vosk ~40 MB / IPA ~150 MB）；
- **红色**"引擎加载失败/模型缺失"：看 `logs/latest.log`。若服务器设置了 `autoDownload = false`，需要管理员手动放置模型文件（见[配置参考](Configuration-zh)）；
- **红色**"麦克风不可用"：见下一条。

## "麦克风不可用" / 没有声音被识别

1. 检查操作系统**麦克风隐私权限**是否放行了 Java/Minecraft；
2. 确认系统默认录音设备正确（VoiceCast 使用系统默认输入设备）；
3. 其他程序（Discord、OBS）独占麦克风时可能冲突——关闭独占模式（Windows：设备属性 → 高级）；
4. Simple Voice Chat / Plasmo Voice 共存已自动处理——见 [SVC 集成](Simple-Voice-Chat-Integration-zh)。

## 识别不到触发词

- **语速放慢、发音清晰**；
- **`vosk-text` 只认英文**——中文/日文请用母语引擎 `vosk-cn` / `vosk-jp`（韩语 `vosk-kr`），IPA 引擎是按音素匹配的替代项；
- IPA 引擎按音素匹配，对非母语发音更宽容（自动容忍松紧元音偏移、吞掉音节尾的辅音）；
- 准星下方的灰色文字是实时识别结果——如果显示的内容离触发词太远，先确认模型下载完整（红色状态行消失）。

## 切换引擎

```
/voicecast engine vosk    # 词语识别（英文，vosk-text）
/voicecast engine zh      # 词语识别（中文，vosk-cn）
/voicecast engine ja      # 词语识别（日文，vosk-jp）
/voicecast engine ko      # 词语识别（韩文，vosk-kr）
/voicecast engine ipa     # 音素识别
/voicecast settings       # 打开选择界面
```

切换立即生效，选择会记住（`config/voicecast/voicecast.toml` 的 `[client] engine`）。服务器可能限制了可用引擎列表（提示 "engine not allowed" 时联系管理员）。

## 模型下载失败（服务器）

- 代理：`-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>`（下载器也会探测 `HTTPS_PROXY`）；
- 自托管/换镜像：`config/voicecast/models.json`；
- 完全离线：`[server] autoDownload = false` + 手动放置——详见[配置参考](Configuration-zh)。

## 高级诊断

- 启动时加 `-Dvoicecast.verbose=true` 输出识别管线日志（`[Mic]`/`[Vosk]`/`[IPA DEBUG]`）；
- 日志：`logs/latest.log`；崩溃看 `crash-reports/`；
- 客户端调试 WAV 录音（源码常量 `VoiceCastConfig.saveDebugWav`，默认关）可证明"录音是否正常"，区别于"识别是否正常"。
