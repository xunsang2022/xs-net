# Game Gateway（网关节点）

Gateway 面向客户端提供 TCP、WebSocket、UDP（轻量可靠 UDP）和 KCP 入口，负责 JWT 首帧认证、C2S 上行路由和 S2C 下行写回。它不直接访问 Redis 或 PostgreSQL；游戏状态和持久化属于 Logic 节点。

- 模块：`game-node-gateway`
- 技术：Java 21、Spring Boot、Netty、Pekko Cluster、Protobuf
- 默认端口：HTTP 8080、Netty TCP 8082、客户端 UDP 2002、Pekko UDP 2001

## 服务流程

```mermaid
flowchart LR
    Client[客户端] -->|HELLO(JWT) / DATA| Netty[Netty TCP 8082]
    Netty --> Auth[AuthHandler]
    Client -->|HELLO/JWT + DATA| UDP[UdpServer 2002]
    UDP --> UdpAuth[UdpAuthHandler]
    Auth --> Registry[ChannelRegistry]
    UdpAuth --> Registry
    Auth -->|Pekko 分片| Player[Logic PlayerActor]
    UdpAuth -->|Pekko 分片| Player
    Player -->|S2C| ChannelActor[Gateway ChannelActor]
    ChannelActor --> Registry
    Registry -->|ClientEndpoint 写回| Client
```

1. 连接建立后，第一个包必须是 `HELLO`，body 为 UTF-8 JWT；网关校验通过后回 `ACK` 完成握手；
2. `AuthHandler` / `UdpAuthHandler` 校验 JWT，读取 `cid` 并登记 `channelId → ClientEndpoint`（TCP 为 `TcpClientEndpoint`，UDP 为 `UdpSession`）；
3. 认证后的 C2S `NetMessage` 按协议号解码，提取 `playerId` 后投递至 Logic 的 `PlayerActor`；
4. Logic 的 S2C 消息以 `channelId` 路由至 `ChannelActor`，后者从注册表取回 `ClientEndpoint` 并写出（TCP 走帧编码，UDP 走可靠层 DATA 包）。

认证失败、认证超时或出现无法恢复的协议解析异常时，Gateway 关闭对应连接/会话。

## 帧协议

### TCP / WebSocket / UDP

TCP、WebSocket binary frame 和自研 UDP 共用 XS packet 格式（大端序）：

```text
length(4) | packetId(2) | sendSeq(4) | random(4) | headCRC(2) | bodyCRC(2) | body(N)
body 长度 | 包类型 | 发送序号 | 逻辑连接随机数 | 头 CRC | body CRC | 包体
```

`length` 表示 body 长度；`headCRC` 校验前 14 字节，`bodyCRC` 校验 body，均为 CRC-16/CCITT-FALSE。`packetId` 为 `DATA=1`、`ACK=2`、`HELLO=3`、`HEARTBEAT=4`、`ERROR=5`。单包默认最大 65536 字节；`rpcNum` 仍位于 `NetMessage` Protobuf body 内。

- **握手**：客户端发 `HELLO(JWT)`，网关校验 JWT 后建立会话并回 `ACK(sendSeq=0)`。
- **TCP/WebSocket**：认证后的消息使用 `DATA`，每个连接维护发送序号。
- **UDP 可靠传输**：`DATA` 带递增 `sendSeq`，接收方按序交付并回累计 `ACK(sendSeq=最高连续序号)`，超时重传在途包；乱序包缓存后补序。
- **心跳/清理**：客户端周期发 `HEARTBEAT`；网关后台按空闲超时清理 UDP 会话。

### 断线重连

客户端重连时应复用同一个 `random`。网关按 `channelId + random` 识别同一逻辑连接：新连接认证成功后替换旧端点，旧端点关闭不会误删新端点。Unity SDK 默认开启自动重连；TypeScript SDK 可使用 `ReconnectingTransport` 包装 TCP/WebSocket/UDP transport，并配置退避和最大重试次数。

当前实现是连接级重连：已提供稳定 `random`、新连接替换和传输层重试，但尚未实现业务状态快照、`baseSeq`、`resyncQueue` 及断线期间业务事件回放。要实现完整状态恢复，还需要由 Logic/PlayerActor 提供玩家快照和可回放的业务事件日志，再接入 `SNAPSHOT(baseSeq) -> seq+1...` 流程。

## 目录结构

```text
src/main/java/com/game/
├── GameGatewayApplication.java
└── gateway/
    ├── actor/ChannelActor.java
    ├── config/GatewayConfig.java
    ├── config/NettyConfig.java
    ├── config/UdpConfig.java
    └── netty/
        ├── AuthHandler.java
        ├── ChannelRegistry.java
        ├── ClientEndpoint.java        # 客户端端点抽象（TCP/UDP 统一写回）
        ├── TcpClientEndpoint.java     # TCP 端点
        ├── NettyChannelInitializer.java
        ├── NettyFramer.java
        ├── NettyServer.java
        ├── NettyServerLifecycle.java  # 同时启动 TCP 与 UDP
        ├── UdpAuthHandler.java
        ├── UdpReliableLayer.java
        ├── UdpServer.java
        └── UdpSession.java
```

## 配置

`src/main/resources/application.yml` 定义 HTTP 端口、Netty 端口、线程数、帧大小、认证超时、空闲超时、连接限制和 JWT 配置，以及 `game.udp.*`（UDP 端口、窗口、重传间隔、会话空闲超时等）。仓库内的 JWT 密钥仅用于本地开发；部署时必须覆盖，并与 Login 节点使用完全相同的密钥。

`application.conf` 定义 Pekko Cluster 配置。多实例部署时，为每个 Gateway 实例配置独立的 Pekko canonical 地址和端口。

## 运行与测试

从仓库根目录构建或测试此模块：

```powershell
.\mvnw.cmd -pl game-server/game-node-gateway -am test
```

完整启动顺序、基础设施和联调客户端说明见 [../README.md](../README.md)。消息注册和路由细节见 [../../xs-net/README.md](../../xs-net/README.md)。
