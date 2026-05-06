# zstdnet-velocity

`zstdnet-velocity` 是运行在 Velocity / VC 代理端的 ZstdNet 桥接插件。

它的定位是 **多子服代理入口**：客户端连接插件暴露的 ZstdNet 端口后，插件负责解压 / 压缩流量，并固定回连到 Velocity 自身的 Minecraft 入口端口。后续服务器选择、`/server` 切服、fallback 等路由语义全部交给 Velocity 处理。

## 推荐部署结构

```text
客户端：ZstdNet mod
Velocity / VC：zstdnet-velocity + Ambassador
Forge 后端：Proxy-Compatible-Forge / PCF
```

## 关键配置

`zstdnet-velocity.properties`：

```properties
bridge_enabled=true
bridge_listen_host=0.0.0.0
bridge_listen_port=35566

bridge_default_target_server=main_area
bridge_upstream_velocity_host=127.0.0.1
bridge_upstream_velocity_port=25565
bridge_upstream_proxy_protocol=false
```

`bridge_default_target_server` 只用于启动校验、UDP 同端口转发和日志显示；TCP 桥接固定回连 Velocity 自身入口，不再提供直连单后端模式。

## 与主线 mod 的关系

- `mods/`：客户端 / 各加载器 ZstdNet mod 主线。
- `vcbgpublic/`：Velocity / VC 代理端桥接插件。
- `zstdnetcore/`：桥接插件复用的内部协议与流量统计代码。
