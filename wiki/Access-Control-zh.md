# [English](Access-Control) | [中文](Access-Control-zh)

# 访问控制

> [← 首页](Home-zh) · 上一篇：[配置参考](Configuration-zh) · 下一篇：[性能与容量](Performance-zh)

## 判定顺序

每个玩家的音频帧 / 控制包 / 引擎选择在服务器入口处按以下顺序判定：

```
[server] enabled = false ?          → 一律拒绝（全服禁用）
   ↓ true
已安装 AccessCheck 钩子？            → 由钩子决定（权限模组桥接）
   ↓ 无钩子
[players] whitelist 为空？           → 空 = 所有人可用
   ↓ 非空
玩家 UUID 在名单中？                 → 是 = 允许 / 否 = 拒绝
```

## 被拒绝的玩家会看到什么

- 音频帧**静默丢弃**（不产生服务器负载）；
- 该玩家**每个进服周期只收到一次**本地化提示（跟随客户端语言）：
  - 全服禁用 → "VoiceCast is disabled on this server / 本服务器已禁用 VoiceCast"
  - 未在白名单 → "you are not allowed to use voice here / 你没有使用语音施法的权限"
- 引擎选择同样被拒——**不会**为未授权玩家创建会话，也就**不会**触发任何模型下载。

## 白名单管理

```toml
[players]
whitelist = [
  "11111111-1111-1111-1111-111111111111",
  "22222222-2222-2222-2222-222222222222"
]
```

- UUID 可通过 `/data get entity <玩家名> UUID`（服务端）或玩家自己的调试屏幕获取；
- 写错格式的 UUID 会被跳过并在日志告警（不影响其他条目）；
- 修改后重启服务器生效。

## 引擎白名单与玩家白名单的关系

- `[engines] allowed` 管**能用哪个引擎**（所有人统一），用于防止玩家选择大模型（如 IPA ~230 MB）；
- `[players] whitelist` 管**能不能用**；
- 两者独立生效：白名单玩家也只能选 `[engines] allowed` 里的引擎。

## 权限模组桥接（预留）

服务端启动后可安装 `com.theo.voicecast.api.AccessCheck`（SAM 接口）覆盖 UUID 白名单判定：

```java
VoiceCastServer.INSTANCE.setAccessCheck(playerId -> permissionMod.has(playerId, "voicecast.use"));
```

- 钩子**优先于** `[players] whitelist`，但 `[server] enabled=false` 仍然一票否决；
- 设计上为 LuckPerms 等 Fabric permissions API / Forge permission 桥接预留（当前版本未内置具体桥接实现）。

## 完全禁用语音（保留玩法）

```toml
[server]
enabled = false
```

服务器不预热任何模型、不加载识别器；玩法模组的卷轴施法不受影响（只是没有语音触发）。
