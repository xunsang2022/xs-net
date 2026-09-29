---
type: Architecture Overview
title: 运行时架构
description: xs-net 的分层与依赖方向、Gateway/Login/Logic/Battle 运行角色、控制面与数据面、线程模型，以及集群与分片策略。
resource: pom.xml
tags: [xs-net, architecture, pekko, netty, redis]
---
# 运行时架构

## 分层与依赖方向

仓库由两个 Maven reactor 组成。`xs-net` 是框架层，`game-server` 是业务层，依赖方向始终指向框架内部：

```text
xs-net-tools (tool-all = tool-commons-lang4 + tool-luBan-cfg)
xs-net-message        NetMessage 信封、RPC 契约、注册表、请求上下文
  -> xs-net-core      Pekko / Cluster Sharding 封装、RpcActor、NodeActor、Spring 自动配置
  -> xs-net-transport 传输抽象（FrameCodec / TransportAdapter / TransportSession / 准入控制）

xs-net-rpc-maven-plugin  JavaParser 扫描 + Velocity 模板：生成 proto、注册表、路由表、SDK
xs-net-sdk               统一 SDK 生成模块（仅打包与生成，不含运行时代码）
xs-net-all               聚合包，导出 xs-net-core + xs-net-transport

game-server
  game-comment           JwtUtil 等公共工具
  game-message-action    业务协议 record、@Rpc/@ProtoField 注解、路由接口、RPC 注册表装配
  game-node-gateway      Spring Boot：Netty 四协议接入、认证、路由、S2C 回写、GM/Actuator
  game-node-login        Spring Boot：仅签发与校验 JWT
  game-node-modules      Spring Boot（非 Web）：PlayerActor、SceneActor、GMActor、Redis、DBSyncEngine、LogConsumer
  game-node-battle       普通 Java：BattleActor、专用战斗执行器池
```

框架层不感知 Netty：`xs-net-transport` 只定义 `FrameCodec`、`TransportAdapter`、`TransportSession`、`TransportMessageHandler`、`BackpressureDecision`、`IngressAdmissionController` 等契约。TCP/WebSocket/UDP/KCP 的具体实现全部在 `game-node-gateway` 内，因为它们绑定了具体的会话与认证语义。

业务协议的唯一来源是 `game-message-action` 中的 Java record。scanner 源码目录同时配置了 `game-message-action`、`game-node-modules`、`game-node-battle`，因此 Actor 里的处理方法也参与协议生成。

## 运行角色

### Gateway（Spring Boot，`game-node-gateway`）

面向客户端的接入层，承担四件事：

1. 终止 TCP 8082、WebSocket 8083、UDP 2002、KCP 2003 四类连接/会话；
2. TCP/WebSocket/自研 UDP 用首个 `HELLO(JWT)` XS packet 完成认证，把 JWT 的 `cid` 绑定为 `channelId` 并写入 `ChannelRegistry`；KCP 保持独立的 KCP 应用层握手；
3. 按 `rpcNum` 解码上行 `NetMessage`，提取 `playerId`，经 Pekko Cluster Sharding 投递到 Logic；
4. 通过 `ChannelActor`（entityId = `channelId`）把 S2C 消息写回 `ClientEndpoint`。

Gateway 还承载 HTTP 管理面：Spring Boot 端口 8080 暴露 Actuator 与 `/admin/gm/*`。它不直连 Redis 或 PostgreSQL，也不保存玩家业务状态。

### Login（Spring Boot，`game-node-login`）

只有 `/api/auth/issue` 与 `/api/auth/verify` 两个端点，使用 `JwtUtil` 签发/校验 JWT。`application.yml` 显式排除 `GameFrameworkAutoConfiguration`，因此 Login 不创建 `ActorSystem`，也不是集群成员。它不创建玩家、不处理游戏状态。

### Logic（Spring Boot 非 Web，`game-node-modules`）

游戏状态与持久化的归属地。`GameLogicApplication` 以非 Web 模式启动 Spring Boot；Boot 管理 Redis、Hikari 数据源及配置绑定，`LogicNodeConfiguration` 提供 MyBatis、DBSync、日志消费和指标组件，`LogicNodeRuntime` 编排初始化并启动 Pekko。Logic 最终启动一个只有一个 `NodeActor` 子节点的 `ActorSystem`（名称为 `game-system`）。节点角色在 `application.conf` 中声明为 `["logic", "system"]`。

Logic 持有的 Actor：

| Actor | role 接口 | 实体 ID | 职责 |
| --- | --- | --- | --- |
| `PlayerActor` | `PlayerMessage`（`@Roles("logic")`） | `playerId` | 登录加载、在线状态、脏数据刷新、登出与销毁 |
| `SceneActor` | `SceneMessage` | 场景 ID | 场景成员集合维护，并向玩家回推场景变更 |
| `GMActor` | `GMMessage` | 固定 `management` | GM 请求审核（审计写入失败即拒绝），再分发 `GmKickPlayerCommand` |

`PlayerActor` 由两个组件构成：`PlayerLifecycle` 负责阶段机与业务消息，`PlayerStateSync` 负责 Redis/数据库读写。两者的异步结果都通过 `pipeToSelf` 回到 Actor 邮箱，保证状态修改仍在单线程内串行。

### Battle（普通 Java，`game-node-battle`）

`@Roles("battle")` 的独立节点，Pekko 端口默认 4501。它在启动时注册 `RpcDeliveryInterceptor`，使 `BattleMessage` 直接进入 `BattleExecutorPool` 而**不进入 Actor 邮箱**——战斗状态、Tick 与高频输入由每场战斗的执行器串行处理，`BattleActor` 只是分片入口与激活点。战斗状态目前只存在于内存。

## 控制面与数据面

```text
控制面（Pekko Cluster）
  各节点 -> seed-nodes（gateway:2001 / logic:4001）
  Cluster Sharding -> ddata 保存实体位置（remember-entities = off）
  NodeActor -> 按本节点角色注册本地实体分片，或为非本地角色创建代理分片

数据面（业务消息）
  客户端 -> Gateway 传输层 -> GatewayMessageRouter
  Gateway -> NetMessage（Protobuf）-> Cluster Sharding entityRef.tell
  Logic/Battle -> NetMessage -> 目标 Actor 或投递拦截器
  Logic -> S2C -> ChannelActor -> ChannelRegistry -> 客户端

数据面（持久化）
  PlayerActor -> Redis hash player:{id}（同步层）
  PlayerActor -> DBSyncEngine（进程内批写器）-> PostgreSQL users 表
  Redis Streams log:events -> LogConsumer -> PostgreSQL log_events 表
```

没有独立的 center/注册中心服务：路由信息来自 Cluster Sharding 本身，`NodeActor` 只负责按角色初始化分片。这与"中心节点分发服务器清单"的架构不同，新增节点不需要向业务中心注册，只需加入 Pekko 集群。

## 消息信封与序列化

所有跨节点与跨网络的消息统一为 `NetMessage`（`xs-net-message/src/main/proto/net_message.proto`）：

| 字段 | 编号 | 用途 |
| --- | --- | --- |
| `rpc_num` | 1 | 路由编号，决定 DTO 类型与目标角色 |
| `data` | 2 | 业务 protobuf 载荷 |
| `error_code` | 3 | 错误码，成功通常为 0 |
| `session_id` / `user_id` | 4 / 5 | 旧协议兼容字段 |
| `user_ip` | 6 | 客户端 IP |
| `trace_id` | 7 | 链路追踪 |
| `deadline_epoch_ms` | 8 | 请求截止时间（epoch 毫秒） |
| `hop_count` | 9 | 转发跳数 |
| `player_id` | 10 | 玩家标识 |
| `channel_id` | 11 | 下行路由的通道标识 |
| `source_node` | 12 | 源节点 |
| `schema_version` | 13 | 上下文协议版本 |

Pekko 远程传输通过 `application.conf` 中的绑定走自定义序列化器：`com.xs.net.message.pb.NetMessage` 由 `NetMessageSerializer`（identifier = 70）编码，`allow-java-serialization = off` 禁止 Java 原生序列化。因此**跨节点投递的载荷只能是 `NetMessage`**；`AkkaKit.tellToRole` 因此在本地投递前也统一转换为 `NetMessage`，避免本地调用绕过序列化边界。

## 线程模型

| 位置 | 线程模型 |
| --- | --- |
| Gateway TCP | boss（默认 1）+ worker（默认 2×CPU）+ business 线程池（默认 2×CPU）。`LengthFieldBasedFrameDecoder` 与 `IdleStateHandler` 在 I/O 线程，`AuthHandler` 挂在 business 线程池 |
| Gateway UDP/KCP | 单 `NioDatagramChannel` I/O 线程 + 固定 business 线程池 + 一个维护调度线程（重传/清理，1 秒周期） |
| Gateway WebSocket | 与 TCP 结构相同，独立端口与独立线程组 |
| Gateway 路由 | `GatewayMessageRouter` 在调用线程上直接投递，只有准入计数使用原子变量 |
| Pekko 默认分片 | `pekko.actor.xs-entity-dispatcher`，线程池 256（`XS_ENTITY_POOL` 可覆盖） |
| PlayerActor | `pekko.actor.player-dispatcher`，线程池 8（`XS_PLAYER_POOL`） |
| SceneActor | `pekko.actor.scene-dispatcher`，线程池 8（`XS_SCENE_POOL`） |
| BattleActor | `pekko.actor.battle-control-dispatcher`，线程池 4；战斗本体在 `BattleExecutorPool` 的 8 个执行器线程 |
| Logic 阻塞 I/O | `redis-io-executor`（默认 16）与 `db-io-executor`（默认 8）两个固定池，队列 1024，拒绝策略 AbortPolicy |
| DBSync | 单调度线程 + `flush-concurrency`（默认 8）个工作线程 |
| LogConsumer | 单个名为 `log-consumer` 的守护线程，阻塞读 Redis Streams |

关键点：实体 Actor 内会做阻塞式外部 I/O，所以分片调度器被显式放大到 256 线程。若新增角色，必须通过 `@ActorDispatcher` 选择合适调度器，否则会落到通用池。

## 集群与分片策略

- 集群名称固定为 `game-system`；`xs-net-message` 的 `application.conf` 提供公共序列化配置，各节点自己的 `application.conf` 追加角色、种子节点与端口。
- 传输使用 Artery 的 `aeron-udp`，内嵌媒体驱动；`embedded-media-driver.idle-cpu-level = 3`（默认 8），用于降低低并发时的唤醒延迟。
- 种子节点：本地开发固定为 `gateway@127.0.0.1:2001` 与 `logic@127.0.0.1:4001`；`application-cluster.conf` 替换为 `gateway-1` / `logic-1` 主机名。
- `numberOfShards = 100`，`remember-entities = off`：实体不持久化位置记忆，重启后由分片重新分配。
- `NodeActor.init` 的逻辑：扫描 `game.framework.actor-base-packages` 下所有具体 `AbstractBehavior` 子类，找到其消息接口上的 `@Roles`；角色匹配本节点则注册**本地实体**，不匹配则为注册表中的每个角色创建**代理分片**（空实现的 `Behaviors.receive`，只用于把消息转发到远端）。
- 因此，一个角色要能被跨节点投递，必须同时满足：实现 `@Roles` 接口、声明 `@Rpc`、且在某个 `RpcActor<Role>` 中有单参数处理方法。

## 静态状态与生命周期风险

以下对象是 JVM 级静态状态，生命周期等于进程生命周期：

- `MessageAble.registry` / `envelopeFactory`：一次性初始化，未初始化时编解码直接抛异常；
- `AkkaKit`：`clusterSharding`、`ENTITY_TYPE_KEYS`、`CREATE_METHODS`、`DELIVERY_INTERCEPTORS` 均为静态缓存；
- `ChannelRegistry.INSTANCE`：Gateway 的 `channelId -> ClientEndpoint` 映射与在线连接数指标；
- `LogicContext`：Logic 的 Redis、DBSync、LogConsumer、执行器与指标的静态装配点，未装配即抛 `IllegalStateException`。

这带来两个工程结论：一是同一 JVM 内不要尝试启动多个互不相干的 RPC 运行时；二是测试或工具需要重置状态时，只能通过显式 API（如 `BattleServiceContext.clear()`、`AkkaKit.unregisterDeliveryInterceptor`），没有通用的 reset 入口。

`RpcDeliveryInterceptor` 是唯一受支持的"绕过 mailbox"扩展点：按 role 注册，返回 `true` 表示已自行处理，返回 `false` 回落到常规分片投递。Battle 节点用它把战斗消息直接交给执行器池。
