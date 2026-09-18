---
type: Engineering Guide
title: xs-net 工程快速上手
description: 面向工程师的入口：仓库定位、构建与测试命令、心智模型、按变更区域索引，以及当前必须遵守的约束。
resource: README.md
tags: [xs-net, java, pekko, quickstart]
---
# xs-net 工程快速上手

## 这个仓库是什么

xs-net 是一个 Java 21 Maven 工作区，一个仓库里包含两层内容：

- `xs-net/`：与具体游戏无关的框架。负责 Pekko Cluster Sharding 封装、RPC 消息契约（`RpcMessageSpec` / `RpcMessageRegistry`）、`NetMessage` 信封、传输抽象，以及客户端 SDK 生成；
- `game-server/`：基于该框架的游戏服务端。包含 Gateway（Netty TCP/UDP/KCP/WebSocket 接入与 S2C 回写）、Login（JWT 签发）、Logic（`PlayerActor`、Redis 状态、异步 PostgreSQL 持久化、日志消费）与 Battle（独立战斗节点）。

根 `pom.xml`（`com.xs:xs-net-workspace`）把两个 reactor 收在同一个构建里；`xs-net/pom.xml` 与 `game-server/pom.xml` 是模块成员和依赖版本的权威来源。业务协议由 `game-server/game-message-action` 中的 Java record 配合 `@Rpc` / `@ProtoField` 声明，`xs-net-rpc-maven-plugin` 在构建期把它们生成为 `.proto`、消息注册表与路由表。

## 从这里开始

1. 在仓库根目录构建整个工作区：

   ```powershell
   .\mvnw.cmd clean install
   ```

   包装脚本固定 JDK 21 与 IntelliJ IDEA 自带 Maven，并显式使用 `%USERPROFILE%\.m2\repository`。不要依赖 PATH 中的全局 `mvn`。

   只构建框架或只构建服务端：

   ```powershell
   .\mvnw.cmd -pl xs-net -am clean install
   .\mvnw.cmd -pl game-server -am clean install
   ```

2. 显式跑测试。测试默认参与 `test` 阶段，不额外关闭：

   ```powershell
   .\mvnw.cmd -pl xs-net -am test
   .\mvnw.cmd -pl game-server -am test
   ```

   单模块 + 上游依赖：

   ```powershell
   .\mvnw.cmd -pl game-server/game-node-modules -am test
   .\mvnw.cmd -pl xs-net/xs-net-rpc-maven-plugin -am test -Dtest=RpcSourceScannerTest
   ```

3. 启动本地依赖与服务：

   ```powershell
   docker compose up -d
   ```

   然后按 Login → Logic（或 Battle）→ Gateway 的顺序启动；端口、配置项与联调方式见 [工程运维](operations/engineering-guide.md)。

4. 协议变更后重新生成客户端 SDK：

   ```powershell
   .\scripts\Generate-Sdks.ps1
   ```

5. 修改跨节点契约前，先读 [领域概念](domain/concepts.md)，再按 [源码地图](architecture/source-map.md) 的入口核对实现。协议编号、角色绑定和线格式是全局契约，静态注册表和注解扫描会让看似局部的改动影响整个 JVM 或整个集群。

## 心智模型

```text
客户端（Unity / TypeScript / Cocos Creator / C++）
  -> TCP 8082 | WebSocket 8083 | UDP 2002 | KCP 2003        首帧：UTF-8 JWT
  -> Gateway：AuthHandler / WebSocketGameHandler / UdpAuthHandler / KcpAuthHandler
  -> GatewayMessageRouter：按 rpcNum 查路由表、解码 DTO、提取 playerId
  -> Pekko Cluster Sharding（Artery / aeron-udp）
  -> Logic：PlayerActor / SceneActor / GMActor　　　Battle 节点：BattleActor
  -> S2C -> ChannelActor(entityId = channelId) -> ChannelRegistry -> ClientEndpoint.write

Login HTTP 8081：签发 JWT（sub = playerId，cid = channelId）
Logic：Redis（player:{id} 状态 + log:events Streams）-> DBSyncEngine / LogConsumer -> PostgreSQL
```

控制面是 Pekko Cluster 自身的成员管理与 Cluster Sharding 的实体位置协调；数据面是 Gateway 与 Logic/Battle 之间的 `NetMessage` 投递。Gateway 不直连 Redis 或 PostgreSQL，Login 不创建玩家也不处理游戏状态。

## 找对地方

| 变更内容 | 从哪里开始 | 用什么验证 |
| --- | --- | --- |
| 新增/修改业务协议（编号、字段、方向） | `game-server/game-message-action` 的 DTO 与 `@Rpc` 注解，再看生成物 `target/generated-sources/rpc` | `xs-net/xs-net-rpc-maven-plugin` 的扫描器测试 + SDK 生成 + `RpcTransportTest` |
| RPC 注册表、信封编解码、请求上下文 | `xs-net/xs-net-message` | `MessageAbleTest`、`NetMessageGoldenTest`、`RequestContextTest`、`RpcRouteRegistryTest` |
| Actor 分片、角色绑定、投递拒绝路径 | `xs-net/xs-net-core`（`AkkaKit`、`NodeActor`、`RpcActor`） | `RpcActorTest`、`AkkaKitRpcRoutingTest` |
| 客户端接入：TCP/WebSocket/UDP/KCP、认证、S2C 写回 | `game-server/game-node-gateway` | Gateway 各传输与路由测试（见 [源码地图](architecture/source-map.md)） |
| 玩家状态、Redis、异步落库、日志消费 | `game-server/game-node-modules` | `PlayerLifecycleTest`、`DBSyncEngineTest`、`LogEventCodecTest` |
| 战斗执行模型与高频输入 | `game-server/game-node-battle` | `BattleActorDispatcherTest`、`BattleExecutorTest`、`BattleLoadTest` |
| JWT 签发与校验 | `game-server/game-node-login` + `game-server/game-comment` | `AuthControllerTest` |
| 客户端 SDK 与模板 | `xs-net/xs-net-sdk`、`xs-net/xs-net-rpc-maven-plugin` | `scripts/Generate-Sdks.ps1` + 各语言模板测试 |

## 当前必须知道的约束

- **协议号是全局契约**。`RpcSourceScanner` 在构建期拒绝重复编号、重复消息名、重复 `@ProtoField` 编号，以及缺少 Actor 处理方法的 `REQUEST` / `INTERNAL` 消息。修改已发布的编号需要兼容性评估。
- **`@Rpc(kind = REQUEST)` 会展开成两条规格**：请求号带 handler，响应号（`responseNumber`）不带 handler。响应 DTO 必须是同一批源码目录中被扫描到的 record。
- **`roleClass` 决定分片实体键**。`AkkaKit` 以 DTO 接口类型的简单类名创建 `EntityTypeKey`；同一 role 接口下的所有消息共享一个分片类型。
- **消息注册表是 JVM 级静态状态**。`MessageAble.initialize(...)` 只初始化一次，`NodeActor` 与各启动类都依赖它。同一 JVM 内不要创建多个独立运行时。
- **首帧认证与游戏帧共用同一种 TCP 分帧**。`LengthFieldBasedFrameDecoder` 先按 4 字节大端长度前缀拆帧，`AuthHandler` 拿到的是帧体；也就是说 JWT 帧同样带长度前缀。
- **`playerId` 与 `channelId` 的取值来源不同**。上行路由用 DTO 中的 `playerId`；下行回写用 JWT `cid` 绑定的 `channelId`，且认证上下文优先于 DTO 中的 `sessionId`。
- **Publisher/队列是有界的，不做投递确认**。Gateway 入站有 4096 全局 + 256 每连接的准入控制，超限直接拒绝并记指标；DBSync 缓冲区满时拒绝新状态；Actor 出错时消息被丢弃而非重试。
- **Logic 不使用 Spring Boot**，Redis、PostgreSQL、Pekko 与 Arthas 由 `GameLogicApplication` 手工装配。配置读取不统一：Redis、数据库、I/O 与刷新周期只从 `application.yml` 读取（环境变量无效），只有管理类参数（`LOGIC_METRICS_*`、`LOGIC_GM_TOKEN`、`LOGIC_ARTHAS_*`、`LOGIC_CONFIG_RELOAD_INTERVAL_MS`）按系统属性 > 环境变量 > YAML > 默认值解析，细节见 [工程与运维](operations/engineering-guide.md#logic--battle)。
- **Pekko 集群要求种子节点可达**。Gateway 默认监听 2001、Logic 4001、Battle 4501，集群名固定为 `game-system`；每个实例必须用 `PEKKO_HOSTNAME` / `PEKKO_PORT` 区分。

## 文档地图

- [架构总览](architecture/overview.md)：分层与依赖方向、运行角色、控制面/数据面、线程模型、集群与分片。
- [模块与源码地图](architecture/source-map.md)：模块职责、关键入口、生成源、测试与跨模块热区。
- [运行时流程](workflows/runtime-flows.md)：启动、接入认证、上行、登录、下行、登出/踢出、持久化、日志、SDK 生成。
- [领域概念](domain/concepts.md)：RPC 契约、方向与可见性、角色、路由、请求上下文、会话、状态与投递语义。
- [集成与扩展](integrations/extensions.md)：四种传输、Spring、Redis/PostgreSQL、可观测性、GM/Arthas、SDK 生成。
- [工程运维](operations/engineering-guide.md)：构建、测试、配置、容量参数、排障、验证矩阵、仓库规范与近期演进。

## 源码优先

实现问题的最终依据是当前源码与 `pom.xml`，而不是散文描述。本 Wiki 中的路径都相对于仓库根目录；如果描述与源码冲突，以源码为准，并顺手修正本 Wiki。

## 待补充证据

- **真实集群的端到端验证**：`deploy/cluster/README.md` 给出了双 Gateway + 双 Logic 的拓扑与压测步骤，但仓库内没有 CI 或脚本化的结果记录，跨节点分片再平衡的实际数据仍待补齐。
- **传输文档与实现的偏移**：`docs/udp-tcp-kcp-websocket.md` 引用的 `com.game.gateway.transport.tcp.*` 类名已不存在，当前实现位于 `com.game.gateway.netty` 与 `com.game.gateway.kcp`。该文档需要随实现一起更新。
- **Battle 节点的持久化与恢复**：`BattleExecutorPool` 目前只在内存中维护战斗运行时，崩溃后的战斗状态恢复路径尚未在源码中体现。
