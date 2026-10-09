---
type: Integration Guide
title: UDP、TCP、KCP 与 WebSocket
description: XS-NET Gateway 四种客户端传输的消息边界、认证、可靠性和统一路由。
resource: game-server/game-node-gateway/src/main/java/com/game/gateway
tags: [xs-net, gateway, tcp, udp, kcp, websocket]
---
# UDP、TCP、KCP 与 WebSocket：XS-NET Gateway 的接入与数据流转

> 本文以当前 `game-node-gateway` 的实现为准，说明四种客户端接入协议的网络语义、项目中的承载格式，以及它们如何汇聚到同一条游戏消息路由链路。

## 先建立一个共同模型

四种入口解决的是不同层次的问题，但在 Gateway 内部的业务目标相同：把经认证的 C2S 消息交给 Logic 的 `PlayerActor`，再将 S2C 消息准确写回对应客户端。

```text
客户端认证 -> 获得 channelId -> 发送 NetMessage -> 按 rpcNum 解码并定位 playerId
                                                    -> PlayerActor
PlayerActor -> S2C + channelId -> ChannelActor -> ClientEndpoint.write(NetMessage) -> 客户端
```

这里有两个容易混淆的标识：

| 标识 | 来源 | 用途 |
| --- | --- | --- |
| `playerId` | C2S 消息 DTO | 将上行消息路由到 Logic 中对应的 `PlayerActor` 分片实体。 |
| `channelId` | JWT 的 `cid` claim | 将下行消息路由回 Gateway 中对应的客户端连接或会话。 |

`NetMessage` 是四个入口认证完成后的共同应用层信封：它由 Protobuf 编码，内部包含 `rpcNum` 和 `data`。传输协议只决定"这些字节如何送到 Gateway"，不改变 RPC 的注册、DTO 解码和 Actor 路由规则。

## 四种协议各自在解决什么问题

| 协议        | 传输语义                           | 项目中的应用消息边界                           | 可靠性与顺序                   | 更适合的接入场景                                 |
| --------- | ------------------------------ | ------------------------------------ | ------------------------ | ---------------------------------------- |
| TCP       | 面向连接的有序字节流                     | `4B BE length + message body`        | 由 TCP 内核保障可靠、有序交付        | 原生客户端、通用游戏长连接、需要简单稳定接入时。                 |
| UDP       | 无连接数据报                         | 一个数据报承载一个项目自定义包                      | 原始 UDP 不可靠；本项目在其上实现轻量可靠层 | 对传输开销敏感、消息不大且可以接受项目自定义可靠语义时。             |
| KCP       | 运行在 UDP 上的应用层可靠传输              | KCP 重组后交付的一条原始应用消息                   | KCP 实现确认、重传、重排、分片重组和窗口控制 | 实时性优先，且需要比 TCP 更灵活的重传与队头阻塞取舍时。           |
| WebSocket | HTTP Upgrade 后的全双工消息通道，底层是 TCP | 一个二进制 WebSocket 帧承载一个原始 message body | 继承 TCP 的可靠、有序特性          | 浏览器、WebView、代理环境或需要复用 HTTP/HTTPS 接入的客户端。 |

"TCP 可靠、UDP 不可靠、KCP 可靠、WebSocket 可靠"只是结果描述。工程上更重要的是可靠性在哪一层实现：TCP/WebSocket 由 TCP 实现；项目的 UDP 由 `UdpReliableLayer` 实现；KCP 由 `Kcp` 状态机实现。它们的拥塞、重传节奏、消息边界和失败处理并不相同。

## TCP：可靠字节流，需要应用层定义帧

TCP 的可靠性、顺序和流量控制由操作系统 TCP 栈提供，但 TCP 只交付连续字节，不能保留一次 `write` 对应的一条游戏消息。因此 Gateway 使用长度字段划分消息：

```text
0                   3 4
+-------------------+-+-------------------------------+
| messageLength (BE) | | message body                  |
+-------------------+-+-------------------------------+
                         首帧：UTF-8 JWT
                         后续：NetMessage protobuf bytes
```

当前实现的处理顺序如下：

1. `NettyServer` 创建 Netty `ServerBootstrap`，`NettyChannelInitializer` 配置 pipeline。
2. `LengthFieldBasedFrameDecoder` 依据前四字节恢复完整帧，避免粘包和半包直接进入业务处理。
3. `AuthHandler` 将首个帧体当作 UTF-8 JWT 校验；成功后将 `cid -> TcpClientEndpoint` 登记进 `ChannelRegistry`。
4. 后续帧体交给 `GatewayMessageRouter`；下行时 `TcpClientEndpoint` 通过 `NettyFramer` 再补回 4 字节大端长度。

TCP 的队头阻塞是它最重要的取舍：某个字节段丢失时，后续字节即使已经到达，应用也必须等待缺失段重传。对于登录、背包、支付、聊天这类要求严格顺序和完整性的消息，这通常是更简单、稳妥的选择。

相关实现：[`NettyServer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/NettyServer.java)、[`NettyChannelInitializer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/NettyChannelInitializer.java)、[`AuthHandler`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/AuthHandler.java)、[`NettyFramer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/NettyFramer.java)。

## UDP：保留数据报边界，项目补上轻量可靠层

原始 UDP 每次发送对应一个数据报，天然保留消息边界，但可能丢包、重复、乱序，也没有连接状态。本项目不是直接把 `NetMessage` 裸发在 UDP 上，而是在 UDP 数据报中定义了固定 15 字节的大端头：

```text
+---------+------+---------+---------+------------+------------------+
| magic   | type | seq     | ack     | payloadLen | payload          |
| 2 bytes | 1 B  | 4 bytes | 4 bytes | 4 bytes    | variable         |
+---------+------+---------+---------+------------+------------------+
  "XS"      DATA / ACK / HELLO / HEARTBEAT / ERROR
```

认证和可靠交付的职责分开：

1. 客户端先发送 `HELLO(JWT)` 数据报。`UdpAuthHandler` 校验 JWT，以远端 `ip:port` 建立会话，并用 JWT 中的 `cid` 创建 `UdpSession`、注册到 `ChannelRegistry`。
2. `UdpSession` 将后续数据报交给 `UdpReliableLayer`。该层维护发送序号、累计确认号、在途窗口和乱序缓存。
3. DATA 包带单调递增的 `seq`；接收方只按连续序号向上层交付，同时回传累计 `ACK`；未确认包在重传间隔到期后重发。
4. 已按序交付的 payload 是完整 `NetMessage` 字节，随后进入统一的 `GatewayMessageRouter`。
5. 下行调用 `UdpSession.write(NetMessage)`，由可靠层编码 DATA 包、缓存未确认项并向记录的远端地址发送。

这是一套"一个大 datagram 对应一个应用消息"的轻量可靠协议，不包含跨数据报的应用消息分片。客户端发送端应把单个 `NetMessage` 控制在路径 MTU 可承受的范围内，避免 IP 分片放大丢包代价。UDP 也没有真正连接，因此会话生命周期依赖心跳和服务端空闲清理；远端地址变化会被视为不同会话。

相关实现：[`UdpAuthHandler`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/UdpAuthHandler.java)、[`UdpReliableLayer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/UdpReliableLayer.java)、[`UdpSession`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/UdpSession.java)、[`UdpServer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/UdpServer.java)。

## KCP：在 UDP 之上构建完整的可靠消息通道

KCP 同样以 UDP 数据报作为底座，但它不复用项目的 `UdpReliableLayer`。`KcpSession` 为每个远端地址维护独立 `Kcp` 实例，KCP 自己处理确认、超时与快速重传、乱序重排、分片与重组、收发窗口和 RTT/RTO 估计。

```text
应用层 message body
        |
        v
Kcp.send / Kcp.recv
        |
        v
KCP segment（conv、命令、序号、确认、窗口、时间戳、分片信息等）
        |
        v
UDP datagram
```

当前 KCP 入口的关键路径是：

1. `KcpServer` 在 `NioDatagramChannel` 上接收 UDP 数据报，将每个远端地址映射为一个 `KcpSession`。
2. `KcpSession.receive` 调用 `Kcp.input(datagram)`，再调用 `Kcp.update(now)` 推进状态机；`Kcp.recv()` 只在一条应用消息完成重组后才返回字节数组。
3. `KcpAuthHandler` 将首条已交付的应用消息作为 UTF-8 JWT 校验，认证成功后把会话作为 `ClientEndpoint` 注册；认证后的已交付消息进入 `GatewayMessageRouter`。
4. 下行调用 `KcpSession.write(NetMessage)`，最终执行 `Kcp.send(message.toByteArray())`；KCP 的 output 回调通过 UDP 写出分段。
5. `KcpServer` 的后台调度器周期调用会话 `updateNow()`，驱动超时重传、窗口更新，并清理认证超时或空闲会话。

KCP 不是"更快的 TCP"这一简单替代品。它需要应用自行调度 `update`，并自行管理 NAT 映射变化、会话清理、带宽和参数调优；但在丢包或抖动网络中，KCP 的可配置重传策略和按消息交付特性常更适合高频实时同步。当前配置通过 `KcpConfig` 控制窗口、更新/重传间隔、认证超时和会话空闲时间。

相关实现：[`Kcp`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/kcp/Kcp.java)、[`KcpAuthHandler`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/kcp/KcpAuthHandler.java)、[`KcpSession`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/kcp/KcpSession.java)、[`KcpServer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/kcp/KcpServer.java)。

## WebSocket：浏览器友好的、有消息边界的 TCP 通道

WebSocket 在 HTTP 连接上完成 Upgrade 后提供全双工帧通道，底层仍是 TCP。因此它具备 TCP 的可靠有序交付，同时 WebSocket 二进制帧本身就是应用消息边界，Gateway 不需要添加 TCP 的 4 字节长度前缀。

```text
HTTP Upgrade: GET /<configured-path> + Upgrade: websocket
                            |
                            v
WebSocket BinaryWebSocketFrame
  首帧 content: UTF-8 JWT
  后续 content: NetMessage protobuf bytes
```

`WebSocketChannelInitializer` 的 pipeline 先经过 `HttpServerCodec`、HTTP 聚合器和 `WebSocketServerProtocolHandler`，再交由 `WebSocketGameHandler`：

1. 首个二进制帧的 content 被解析为 JWT；成功后注册 `WebSocketClientEndpoint`。
2. 后续二进制帧直接交给 `GatewayMessageRouter`，其内容就是 `NetMessage` 的 Protobuf 字节。
3. `ChannelActor` 下行写回 `WebSocketClientEndpoint` 时，用 `BinaryWebSocketFrame` 包装 `NetMessage.toByteArray()`。
4. TCP 连接断开、认证超时或读空闲时，处理器清理 `ChannelRegistry` 中的注册项并关闭通道。

浏览器使用 WebSocket 时应显式发送二进制数据，而非 JSON 文本帧；这才能与现有 Protobuf `NetMessage` 契约一致。若需要 TLS，应由部署层提供 `wss://`，而不是改变这条业务消息链路。

相关实现：[`WebSocketServer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/WebSocketServer.java)、[`WebSocketChannelInitializer`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/WebSocketChannelInitializer.java)、[`WebSocketGameHandler`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/WebSocketGameHandler.java)、[`WebSocketClientEndpoint`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/WebSocketClientEndpoint.java)。

## 四种接入最终如何汇聚

认证之后，协议专属处理停止承担业务路由职责。四种入口都以 `channelId` 注册一个 `ClientEndpoint`，因此下行只依赖统一抽象，不需要在 `ChannelActor` 内判断 TCP、UDP、KCP 或 WebSocket。

### 上行：Client 到 PlayerActor

1. 协议入口完成各自的消息边界恢复、认证和可靠交付处理。
2. `GatewayMessageRouter.route` 将字节解析为 `NetMessage`，依据 `rpcNum` 从 RPC 注册表找到契约。
3. 契约解码 `data` 为游戏 DTO；路由器从 DTO 提取 `playerId`。
4. `AkkaKit` 将消息投递给 Logic 节点的 `PlayerActor` 分片实体。本地目标可以直接投递对象，远端目标按 `NetMessage` 进行跨节点传输。

### 下行：PlayerActor 到 Client

1. Logic 产生携带 `channelId` 的 S2C 消息，并投递给 Gateway 的 `ChannelActor` 分片实体。
2. `ChannelActor` 将 S2C 转为 `NetMessage`，从 `ChannelRegistry` 找到对应 `ClientEndpoint`。
3. `ClientEndpoint.write` 由具体实现决定写回形式：TCP 加长度前缀、UDP 走 `UdpReliableLayer`、KCP 走 `Kcp.send`、WebSocket 包装二进制帧。
4. 找不到端点或端点已失效时，Gateway 只记录下行丢弃，不会主动重连客户端；离线补偿属于业务层职责。

相关实现：[`GatewayMessageRouter`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/GatewayMessageRouter.java)、[`ChannelRegistry`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/ChannelRegistry.java)、[`ClientEndpoint`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/netty/ClientEndpoint.java)、[`ChannelActor`](../../game-server/game-node-gateway/src/main/java/com/game/gateway/actor/ChannelActor.java)。

## 如何选择

| 需求 | 建议入口 | 原因 |
| --- | --- | --- |
| 账号、背包、战报、聊天等强一致业务 | TCP | 协议成熟，运维、抓包和客户端实现成本最低。 |
| 浏览器或 WebView 客户端 | WebSocket | 浏览器原生支持，保持二进制 Protobuf 可复用同一业务协议。 |
| 高频状态同步，允许接受 KCP 运维与参数调优成本 | KCP | 在 UDP 上提供可靠消息交付，并可独立控制更新与重传策略。 |
| 小消息、可控 MTU、希望使用轻量项目内可靠层 | UDP | 头部和行为简单，已提供顺序、确认与重传；但没有消息分片能力。 |

不应仅因"实时游戏"就把所有消息切到 UDP 或 KCP。需要严格事务语义或天然顺序依赖的业务，仍可能更适合 TCP/WebSocket；实时同步也应结合消息丢失是否可容忍、单包大小、目标网络环境、带宽限制和客户端平台决定。

## 接入协议不改变的安全与运维规则

- 四种入口均以 JWT 作为首个应用消息认证，必须校验 `cid`，并受认证超时和单账号连接限制约束。
- `channelId` 是下行寻址键，不是玩家实体键；玩家处理必须从经过契约解码的 C2S DTO 中取得 `playerId`。
- TCP、WebSocket 的读空闲由 Netty pipeline 管理；UDP、KCP 必须通过会话活动时间与后台清理避免无连接会话泄漏。
- UDP 与 KCP 的会话按远端地址管理，NAT 重新映射会带来新地址；客户端重连和业务幂等应能够处理旧会话失效。
- 所有协议最终承载的都是受 RPC 注册表约束的 `NetMessage`，不要把 TCP 长度前缀、UDP 可靠头或 KCP segment 当成跨节点的 Actor 消息格式。

## 延伸阅读

- [领域概念](../../framework/xs-net-message/concepts.md)：`NetMessage`、RPC 契约、分片路由和 Actor 侧细节。
- [Gateway 模块说明](../../game-server/game-node-gateway/README.md)：端口、配置项和模块目录。
- [架构总览](../../general/architecture/overview.md)：Gateway、Login、Logic、Redis 和 PostgreSQL 的服务边界。
