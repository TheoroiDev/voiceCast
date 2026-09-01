# [English](Simple-Voice-Chat-Integration) | [中文](Simple-Voice-Chat-Integration-zh)

# Simple Voice Chat 集成

> [← 首页](Home-zh) · 相关：[配置参考](Configuration-zh)（`[compat]` 节）

VoiceCast 对 [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)（SVC）提供**一等公民级的共存集成**。它是**可选的**：未安装 SVC 时行为不变；安装后两个模组智能共享麦克风。

## 工作原理

- VoiceCast 注册一个 SVC 插件（`VoicechatPlugin`，id `voicecast`）——Fabric 经 `voicechat` entrypoint，Forge 经 `@ForgeVoicechatPlugin` 注解扫描。插件类只在 SVC 已安装时被实例化；
- 插件**只观察** SVC 的客户端状态（连接、麦克风静音、采包），写入无锁快照；
- VoiceCast **从不取消或修改** SVC 音频，SVC 也不碰 VoiceCast 管线。

## 共存模式（`voicecast.toml [compat] svcCoexistence`）

| 模式 | 行为 |
|---|---|
| `share`（默认） | VoiceCast 照常开麦；若 SVC 最近 250ms 内有采包，记录一条 info 日志。设备通常可共享（SVC 走 OpenAL capture，VoiceCast 走 Java Sound），且 VoiceCast 在 PTT 松开瞬间释放线路。 |
| `defer` | SVC 最近在收音 → VoiceCast **推迟开麦**（每 tick 重试）。等待超过 2 秒（如 SVC 处于语音激活模式且用户一直说话）则**自动回落 share** 而不是永久阻塞——回落时每次按键记录一次 info。 |

## 失败韧性

麦克风设备被独占（打开失败）时，VoiceCast 记录明确日志（"Simple Voice Chat is active and may hold the device"）并在 **500ms 后重试一次**——瞬时占用通常瞬间恢复。下一次按键仍会重试。

## 服务器配置

无需任何操作——共存自动生效。需要更严格行为的服务器设置：

```toml
[compat]
svcCoexistence = "defer"
```

按服务器生效，对所有玩家适用。

## 已知交互（设计如此）

你念咒语时**其他玩家能通过 SVC 听到**——你毕竟在大声念。VoiceCast 不会替你静音 SVC。
