# ZstdNet Velocity
使用前必要的安装
- [TrueUUID正版离线共存（客户端/服务端）](https://www.curseforge.com/minecraft/mc-mods/trueuuid)
- [Ambassador（Velocity）](https://github.com/adde0109/Ambassador/releases/tag/v1.4.5)
- [Proxy-Compatible-Forge（客户端）](https://github.com/adde0109/Proxy-Compatible-Forge/releases)

`zstdnet-velocity` 是给 **Velocity / VC 代理端** 使用的 ZstdNet 插件。

它的作用很简单：

> 让装了 ZstdNet 的客户端先连接到代理端的压缩入口，然后由 Velocity 正常转发到后端服务器。

适合这种结构：

```text
玩家客户端
  └─ 安装 ZstdNet mod
  └─ 连接 你的域名:35566

Velocity / VC 代理
  ├─ 安装 zstdnet-velocity 插件
  ├─ 安装 Ambassador 插件（Forge 服务器推荐/必要）
  └─ 监听普通入口 25565 + ZstdNet 入口 35566

Forge 后端服务器
  ├─ 不需要安装 ZstdNet 服务端 mod
  └─ 安装 Proxy-Compatible-Forge / PCF（Forge 后端推荐/必要）
```

## 需要安装什么

### 1. 客户端

客户端安装对应版本的 **ZstdNet mod**。

玩家连接地址示例：

```text
你的域名:35566
```

不要让玩家连接 `25565`，否则不会走 ZstdNet 压缩入口。

### 2. Velocity / VC 代理端

把这些插件放进 Velocity 的 `plugins/` 目录：

```text
zstdnet-velocity
Ambassador-Velocity
```

说明：

- `zstdnet-velocity`：负责 ZstdNet 压缩入口。
- `Ambassador`：负责让 Velocity 正确处理 Forge / FML 握手。

如果你是 Forge 1.20.1 这类后端，**不要禁用 Ambassador**。
禁用后常见现象是：客户端明明装了 Forge，却被后端/代理当成 vanilla 客户端，出现类似：

```text
This server has mods that require Forge to be installed on the client.
Channels [...] rejected vanilla connections
```

### 3. Forge 后端服务器

Forge 后端推荐安装：

```text
Proxy-Compatible-Forge / PCF
```

PCF 负责后端侧的 Velocity modern forwarding / CrossStitch 兼容。

注意：

- PCF 是后端 Forge mod。
- Ambassador 是 Velocity 插件。
- 它们不是同一个东西，也不能互相替代。

---

## 推荐 Velocity 配置

编辑 Velocity 的 `velocity.toml`。

推荐当前稳定配置：

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
ping-passthrough = "DISABLED"
announce-forge = true
announce-proxy-commands = true
```

如果希望多人游戏列表显示 VC / Velocity 入口自己的 MOTD、版本、玩家数，`ping-passthrough = "DISABLED"` 很重要。
在 `ALL` / `DESCRIPTION` 等透传模式下，Velocity 可能使用后端的完整 ping 响应或描述信息，客户端看到的就可能是后端服务器信息。

`announce-proxy-commands = true` 很重要。
如果关掉，客户端可能看不到 `/server` 这类 Velocity 命令。

`forwarding.secret` 示例：

```text
wish1314
```

这个 secret 要和后端 PCF 配置里的 secret 一致。

---

## 推荐 PCF 配置

在每个 Forge 后端的配置文件里：

```text
config/proxy-compatible-forge.toml
```

推荐配置：

```toml
[forwarding]
enabled = true
mode = "MODERN"
secret = "wish1314"
approvedProxyHosts = []

[crossStitch]
enabled = true
forceWrappedArguments = []
forceWrapVanillaArguments = false

[debug]
enabled = false
disabledMixins = []
```

其中 `secret` 必须和 Velocity 的 `forwarding.secret` 文件内容一样。

后端自己的 `server.properties` 建议：

```properties
online-mode=false
```

---

## zstdnet-velocity 配置

插件第一次启动后会生成：

```text
plugins/zstdnet-velocity/zstdnet-velocity.properties
```

常用配置如下：

```properties
# 是否启用 ZstdNet 桥接入口
bridge_enabled=true

# ZstdNet 入口监听地址
# 0.0.0.0 表示所有网卡都监听
bridge_listen_host=0.0.0.0

# 玩家连接的 ZstdNet 入口端口
bridge_listen_port=35566

# 默认目标服务器名
# 这里要写 Velocity velocity.toml 里 [servers] 的服务器名
# 主要用于启动检查、日志显示、UDP 同端口转发
bridge_default_target_server=main_area

# Velocity 自己的普通 Minecraft 入口
# 通常就是 127.0.0.1:25565
bridge_upstream_velocity_host=127.0.0.1
bridge_upstream_velocity_port=25565

# 插件 -> Velocity 方向是否发送 PROXY v2 头，可选 auto / true / false
# auto 会尽力读取 velocity.toml 的 [advanced] haproxy-protocol；读不到时降级为 false 并输出警告
bridge_upstream_proxy_protocol=auto

# 前置代理 / FRP / LB -> 插件方向是否接受入站 PROXY v2 头，可选 auto / true / false
# auto 只在 bridge_listen_host 是 localhost / 127.0.0.1 / ::1 等回环地址时启用
bridge_inbound_proxy_protocol=auto

# 是否把后端 Set Compression 阈值改写为 1048576
# 默认 false 会保留 Velocity/后端原始阈值，兼容 DAC 等会检查代理端报文形态的反作弊
bridge_rewrite_compression_threshold=false

# 是否由 Velocity 侧查询 Mojang 并补齐正版 UUID/签名皮肤 profile
# 遇到登录后断开时，可先改成 false 交给 Velocity/PCF/TrueUUID/反作弊链路自行处理
premium_profile_forwarding=true

# 是否向 ZstdNet 客户端发送代理端 HUD 统计自定义通道
# 默认 false，优先兼容代理端反作弊；确认客户端 ZstdNet 版本匹配后再打开
client_hud_sync=false

# 自定义 UDP 放行/转发规则，多个规则用英文逗号或分号分隔
# 只写端口表示监听该 UDP 端口并转发到默认后端同端口
# 完整格式：监听地址:端口->目标地址:端口
udp_custom_routes=24454, bedrock=0.0.0.0:19132->127.0.0.1:19132
```

说明：

- `35566` 是给玩家连的 ZstdNet 压缩入口。
- `25565` 是 Velocity 自己原本的 Minecraft 入口。
- 插件现在固定走多子服代理模式，不再提供直连单后端模式。
- `/server`、fallback、try 列表、forced-hosts 等都继续用 Velocity 原生配置。
- `bridge_upstream_proxy_protocol` 是“插件到 Velocity”的方向；设为 `true` 时，Velocity 的 `velocity.toml` 也必须开启 `[advanced] haproxy-protocol = true`。
- `bridge_inbound_proxy_protocol` 是“前置代理到插件”的方向；公网监听 `0.0.0.0` 时不要直接信任客户端自带的 PROXY v2，只有确认桥接端口只被可信 FRP/LB 访问时才显式设为 `true`。
- 入站 PROXY v2 只用于恢复玩家真实来源 IP；插件发给 Velocity 的 PROXY v2 目标地址始终是实际配置的 Velocity upstream，不透传前置代理传入的 target。
- `bridge_rewrite_compression_threshold=false` 会保留 Velocity / 后端原始 vanilla compression 阈值，避免把 `256 -> 1048576` 这类阈值改写暴露给代理端反作弊；只有确认插件链路兼容且更看重压缩效率时才设为 `true`。
- `premium_profile_forwarding=false` 会跳过 Velocity 侧 Mojang profile 修正，适合排查 DAC / TrueUUID / PCF 在登录后断开的兼容问题。
- `client_hud_sync=false` 不会向客户端发送 `zstdnet:*` HUD 自定义通道包；这是默认值，优先保证能进服。需要客户端 HUD 统计时再改为 `true`。
- `udp_custom_routes=24454` 会监听 `bridge_listen_host:24454` 并转发到默认后端的 `24454/udp`；也可以写成 `label=0.0.0.0:19132->127.0.0.1:19132` 指定日志名和目标。
- `client_hud_sync=true` 时，安装 1.4.2 或更新版本 ZstdNet 客户端 mod 的玩家会收到 Velocity 侧服务端 HUD 统计；插件会向 `zstdnet:server_hud` 以及 Forge 1.20.1 的 `zstdnet:lan_compression` 兼容通道发送快照。

---

## Velocity 子服配置示例

`velocity.toml` 示例：

```toml
[servers]
main_area = "127.0.0.1:25566"
mygo = "127.0.0.1:35573"

try = ["main_area"]
```

玩家连接：

```text
你的域名:35566
```

进入服务器后可以使用：

```text
/server mygo
```

---

## 常见问题

### 客户端看不到 `/server` 命令

检查 Velocity：

```toml
announce-proxy-commands = true
```

改完后重启 Velocity。

### 禁用 Ambassador 后进不去

这是正常的。

Forge 后端需要 Forge / FML 握手兼容。Ambassador 是 Velocity 侧处理这个握手的插件。
禁用后，代理可能把 Forge 客户端当成 vanilla 客户端。

### 后端提示需要 Forge 客户端，但客户端明明装了 Forge

通常是 Ambassador 没加载，或者 Forge/FML 握手没有被代理正确处理。

检查 Velocity 启动日志里有没有：

```text
Loaded plugin ambassador
```

### 出现 `Argument type identifier ... unknown`

这是 Velocity 不认识 Forge/modded 命令参数类型。

确保 Forge 后端安装并启用了 PCF 的 CrossStitch：

```toml
[crossStitch]
enabled = true
```

### 后端提示 `This server requires you to connect with Velocity.`

说明 PCF forwarding 开了，但 Velocity 没有按对应方式转发。

检查：

1. Velocity 是否是 `player-info-forwarding-mode = "modern"`
2. PCF 是否是 `mode = "MODERN"`
3. Velocity 的 `forwarding.secret` 是否和 PCF 的 `secret` 完全一致

### 玩家皮肤 / UUID 问题

当前推荐稳定模式是：

```toml
Velocity online-mode = false
player-info-forwarding-mode = "modern"
```

这种模式可以稳定配合 TrueUUID + ZstdNet 使用。

如果你强制把 Velocity 改成 `online-mode=true`，理论上可以拿到正版 UUID/皮肤，但 Forge/Ambassador/FML 握手链路可能变得不稳定，具体要按整合包测试。


## 许可证

本项目遵循 MIT License。
