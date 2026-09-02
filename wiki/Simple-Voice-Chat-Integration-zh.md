# [English](Simple-Voice-Chat-Integration) | [中文](Simple-Voice-Chat-Integration-zh)

# Simple Voice Chat 集成

> [← 首页](Home-zh) · 相关：[配置参考](Configuration-zh)（`[compat]` 节）

VoiceCast 对 [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)（SVC）提供**一等公民级的共存集成**。它是**可选的**：未安装 SVC 时行为不变；安装后两个模组智能共享麦克风。

## 工作原理

- VoiceCast 注册一个 SVC 插件（`VoicechatPlugin`，id `voicecast`）——Fabric 经 `voicechat` entrypoint，Forge 经 `@ForgeVoicechatPlugin` 注解扫描。插件类只在 SVC 已安装时被实例化；
- 插件**只观察** SVC 的客户端状态（连接、麦克风静音、采包），写入无锁快照；
- VoiceCast **从不取消或修改** SVC 音频，SVC 也不碰 VoiceCast 管线。

## 共存（仅 share）

VoiceCast 与 SVC **共享**麦克风：照常开麦；若 SVC 最近 300ms 内有采包，记录一条 info 日志。设备通常可共享（SVC 走 OpenAL capture，VoiceCast 走 Java Sound），且 VoiceCast 在 PTT 松开瞬间释放线路。

> 原有的 `defer` 模式（SVC 收音时推迟开麦）**已移除**——它只会延迟玩家主动施法，而咒语仍会通过 SVC 自己的采集进入语音频道（voicecast#27）。配置里写 `svcCoexistence = "defer"` 会回落到 `share` 并记录一次日志。

## 失败韧性

麦克风设备被独占（打开失败）时，VoiceCast 记录明确日志（"Simple Voice Chat is active and may hold the device"）并在 **500ms 后重试一次**——瞬时占用通常瞬间恢复。下一次按键仍会重试。

## 设置

无需任何操作——共存自动生效，且始终为 share。`[compat] svcCoexistence` 键为配置兼容保留（写 `"defer"` 会被接受但回落 share），它是**客户端本地设置**：每个玩家各自的配置文件，只作用于本人——服务端不读取也不同步。

## 已知交互（设计如此）

你念咒语时**其他玩家能通过 SVC 听到**——你毕竟在大声念。VoiceCast 不会替你静音 SVC。
