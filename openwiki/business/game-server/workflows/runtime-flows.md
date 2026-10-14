---
type: Workflow Guide
title: 运行时流程
description: 启动与集群加入、四协议接入认证、上行路由、登录加载、S2C 下行、登出与踢出、状态持久化、日志消费、协议与 SDK 生成、指标采集。
resource: game-server/game-node-gateway/src/main/java/com/game/gateway/netty/AuthHandler.java
tags: [xs-net, workflows, gateway, logic]
---
# 运行时流程

## 启动与集群加入

三个服务进程的启动路径不同，但最终都汇聚到 `NodeActor` 的角色扫描与分片注册。

### Gateway（Spring Boot）

```text
GameGatewayApplication.main
  -> MessageAble.initialize(GameRpcMessages.INSTANCE)
  -> 打印全部路由（number/direction/role/public/route/transports）
  -> SpringApplication.run
       -> GameFrameworkAutoConfiguration：ActorSystem("game-system") + NodeActor 子节点 + ClusterSharding bean
       -> CommentConfig：JwtUtil
       -> 各传输 Server 启动（Netty TCP / UDP / KCP / WebSocket）
```

### Login（Spring Boot，非集群成员）

`application.yml` 通过 `spring.autoconfigure.exclude` 排除 `GameFrameworkAutoConfiguration`，因此不创建 ActorSystem。启动只装配 `JwtUtil`（签名密钥来自环境变量 `JWT_SECRET`，为空或不足 32 字节即启动快速失败）与 `AuthController`（`/api/auth/issue` 内置按客户端 IP 的 `ClientIpFixedWindowRateLimiter` 固定窗口限流）；Actuator 仅暴露 `health,info`。

### Logic（Spring Boot，非 Web）

```text
GameLogicApplication.main
  -> Spring Boot 非 Web 容器（绑定 application.yml / 环境变量 / 命令行配置）
  -> 自动配置 RedisConnectionFactory + StringRedisTemplate、Hikari DataSource
  -> LogicNodeConfiguration 创建 RedisStore、MyBatis、DBSync、LogConsumer、指标组件
  -> LogicNodeRuntime 装配 LogicContext，启动 DBSync、管理端点、热配置和 LogConsumer
  -> ActorSystem("game-system") + NodeActor
  -> Spring 容器关闭时终止 ActorSystem、停 LogConsumer、flush+stop DBSync 并关闭自有资源
```

Logic 启动耗时对比（WSL Ubuntu 24.04、JRE 21、本地 Redis，同 JVM 参数与应用配置；从 `[BENCHMARK_START]` 到 Shard Region 开始初始化）：普通 Java 基线约 **16.0 秒**，Spring Boot 版本约 **20.1 秒**，本次增加约 **4.1 秒**。Spring Boot 日志中的 `Started ... in 8.336 seconds` 是应用上下文就绪耗时，不包含完整的 Pekko 初始化，也不是进程总启动时间。测量未等待 Pekko 集群加入：测试时配置的 seed node `127.0.0.1:2001` 未运行；基线与新版本均以本地节点及 Shard Region 初始化为终点。该结果只代表这次本机测量。

Battle 的启动仍为普通 Java：初始化 `MessageAble`、构造 `BattleExecutorPool` 与 `BattleCommandIngress`、`AkkaKit.registerDeliveryInterceptor(BattleMessage.class, ingress)`，然后启动 ActorSystem 并在关闭钩子里反注册拦截器。

### `NodeActor` 做什么

```text
NodeActor.init
  -> 取当前节点 roles（Pekko Cluster selfRoles），去掉 "dc-default"
  -> 确保 RpcMessageRegistry 已初始化（Spring bean 优先，否则用 MessageAble 的静态实例）
  -> 扫描 actor-base-packages：
       角色命中本节点 -> initClusterSharding（本地实体，反射调用 create/create(String)）
  -> 遍历注册表中的 roleClass：
       角色不属于本节点 -> entityRefForClusterSharding（空代理分片）
```

顺序很重要：协议注册表必须先可用，否则非本地角色的代理分片无法创建，跨节点投递会在运行时才失败。

## 客户端接入与认证

TCP、WebSocket 和自研 UDP 共享同一模型：**首个 `HELLO(JWT)` 包 → 绑定 `channelId` → 后续 `DATA` 包承载 `NetMessage`**；KCP 使用独立的 KCP 应用层握手。认证前 `auth-timeout-seconds`（默认 10 秒）到期即断开；认证通过后触发 `max-connections-per-account` 检查。

### TCP（8082）

```text
连接建立
  -> LengthFieldBasedFrameDecoder(maxFrameLength=65536, offset=0, length=4, strip=0)
  -> IdleStateHandler(readerIdle=300s)
  -> AuthHandler（挂 business 线程池）

首帧：XS packet(packetId=HELLO)，body = UTF-8 JWT
  -> AuthSupport.resolve：jwtUtil.validateToken -> Claims，channelId = claims.cid，缺失即失败
  -> AuthSupport.admitReconnect(channelId, packet.random(), max-connections-per-account)
       （同 channelId 已有活跃连接：random 相同则替换旧的，否则拒绝并计 gateway.connection.rejected）
  -> ChannelRegistry.register(channelId, TcpClientEndpoint)
  -> AuthSupport.markLoginIngress(channelId)：认证成功即记录登录入口时间戳
  -> AuthSupport.countSuccess / countFailure：gateway.auth.success / gateway.auth.failure

后续帧：XS packet(packetId=DATA)，body = NetMessage Protobuf 字节 -> GatewayMessageRouter.route(body, channelId)
```

注意：由于拆帧在 `AuthHandler` 之前，**JWT 帧同样使用 XS packet，前 4 字节是 body 长度字段**，JWT 位于 `HELLO` 包的 body 中。实现与 SDK 都遵循完整 XS packet 格式，而不是简单的"长度前缀 + NetMessage"。

连接断开时 `channelInactive` 从 `ChannelRegistry` 移除并清理认证超时任务。

TCP、UDP、KCP 三处 `authenticate` 的主干共用 `com.game.gateway.netty.AuthSupport`：`resolve`（验签 → channelId）、`admitReconnect`（单账号并发校验）、`countSuccess`/`countFailure`/`countC2S`、`markLoginIngress`（认证成功即记录登录入口时间戳），以及 `TCP_METRICS`/`UDP_METRICS`/`KCP_METRICS` 指标名常量；各 Handler 只保留握手回执、会话创建/清理等传输特化逻辑。

### WebSocket（8083，路径 `/game`）

与 TCP 等价，只是帧类型为 `BinaryWebSocketFrame`，首个 binary frame 承载 `HELLO(JWT)` XS packet。使用独立的端口、线程组与 `WebSocketConfig`，指标前缀为 `gateway.websocket.*`。

### UDP（2002）

```text
DatagramPacket
  -> 拷贝字节后立即提交 business 线程池（避免单 event loop 串行）
  -> UdpReliableLayer.decode
       type = HELLO -> authenticate：payload 为 JWT -> 创建 UdpSession 并注册
       其他        -> sessions.get(sender) -> synchronized(session) { session.receive(bytes) }
```

每个会话串行、不同会话并行；后台调度线程每秒执行 `retryAndCleanup`（未确认包重传 + 空闲/认证超时清理）。指标前缀 `gateway.udp.*`。TCP、WebSocket 和自研 UDP 的应用包均为 `length(4) | packetId(2) | sendSeq(4) | random(4) | headCRC(2) | bodyCRC(2) | body(N)`。

### KCP（2003）

`KcpAuthHandler` 与 `KcpServer` 用同一套结构承载标准 KCP：握手阶段校验 JWT，之后由后台调度线程周期驱动 `Kcp.update()` 与超时清理。窗口与节拍由 `game.kcp.window` / `game.kcp.retry-interval-ms` 控制。

### XS packet 与 UDP 可靠层

```text
length(4) | packetId(2) | sendSeq(4) | random(4) | headCRC(2) | bodyCRC(2) | body(N)
```

`length` 是 body 长度；两个 CRC 均为 CRC-16/CCITT-FALSE。发送方向维护在途窗口并超时重传；接收方向用 `ConcurrentSkipListMap` 做重排序后按序交付。`random` 在自动重连期间保持不变，用于识别同一逻辑会话。

## C2S 上行路由

```text
GatewayMessageRouter.route(body, channelId)
  -> 准入：全局 4096 在途 + 每 channelId 256 在途；任一失败则
         记录 gateway.ingress.rejected{reason=capacity} 并直接丢弃
  -> routes.find(rpcNum)：未知编号仅打印 WARN 后返回
  -> spec.parser().parseFrom(data) -> spec.decode(payload) 得到 DTO
  -> route.playerId(dto)：为空则丢弃
  -> 组装 RequestContext（withIdentity 覆盖 channelId；过期则
         记录 gateway.ingress.rejected{reason=expired} 并丢弃）
  -> AkkaKit.tell(GameRpcMessages.INSTANCE, PlayerMessage.class, dto, playerId, context)
       LoginRequest(1000) / LogoutRequest(1001) 等 DTO 均按解码结果直接投递到 PlayerMessage 角色，
       认证通道通过 RequestContext.withIdentity(playerId, channelId) 覆盖到上下文
```

关键设计：`LoginRequest(1000)` 直接投递到 `PlayerMessage`，不再转换为内部协议；GM 追踪接口在追踪登录时按 `1000` 过滤。

投递到 Pekko 后的路径见 [领域概念](../../framework/xs-net-message/concepts.md#路由与身份)：校验 → 拦截器 → `entityRef.tell(NetMessage)`；跨节点由 Artery 经 `NetMessageSerializer` 传输，远端 `RpcActor.onNetMessage` 解码、恢复上下文并 `tell` 给自己。

## 登录加载与首次响应

`PlayerActor` 在构造期即完成玩家数据加载：`PlayerInfo.loadingModel(playerId)` 先读 Redis 缓存 `player:{playerId}`，未命中再经 `DBSyncEngineRegistry.loadOrCreate("player", playerId)` 读数据库（`INSERT ... ON CONFLICT ... RETURNING`），并把结果回写 Redis。加载是同步的，没有阶段机、加载超时定时器或消息暂存。

`PlayerInfo` 直接处理 `LoginRequest(1000)`：

```text
LoginRequest(1000) -> PlayerActor -> PlayerInfo.onLoginMessage
  -> 已在线则忽略
  -> 记录 pendingChannelId；name 变化则 setName（标脏）
  -> markOnlineFields：online=1、last_login_at/updated_at、version+1（标脏）
  -> online = true
  -> redisStore.saveRedis(data)
  -> refreshFlushTimerIfNeeded()（启动脏数据刷新单次定时器）
  -> services.sendRpc(pendingChannelId, LoginResponse(0, null, data.toVo()))
  -> 记录 login.s2c.send.duration / login.logic.total.duration
```

上述登录埋点均已接线：`PlayerInfo.loadingModel` 分别记录 Redis 命中/未命中/读失败的 `login.redis.load.duration{cache=hit|miss|failure}` 与 DB 加载的 `login.database.load.duration`，登录响应路径记录 `login.s2c.send.duration`（S2C 发送耗时 Timer）与 `login.logic.total.duration`。`login.redis.create.duration` 仅在 `LogicMetrics` 中定义（预留），登录路径当前未调用 `recordLoginRedisCreate`。

## S2C 下行

```text
Logic：services.sendRpc(response, channelId)
  -> AkkaKit 校验角色 -> 转 NetMessage -> entityRef.tell
  -> 远端（Gateway）ChannelActor(entityId = channelId)
       -> ChannelRegistry.get(channelId) -> ClientEndpoint
       -> 空或 !isActive() -> gateway.s2c.dropped 计数 + WARN
       -> MessageAble.toNetMessage + applyTo(RequestContext)
       -> endpoint.write(netMessage)（各自补上传输所需的分帧）
       -> gateway.s2c.messages 计数；若存在登录入口时间戳则记录 gateway.login.roundtrip
```

`S2C` 接口带 `@Roles("gateway")`，因此 Logic 节点会为它创建代理分片，消息自动路由到持有该 `channelId` 实体的 Gateway 实例。

## 登出、GM 踢出与销毁

### 客户端登出（1001 → 1003）

```text
LogoutRequest(1001) -> PlayerActor -> PlayerInfo.onLogoutMessage
  -> 若在线：beginDestroy()
       online=false、cancelFlushTimer
       标记 online=0 / updated_at 脏字段
       ActorServerSync.destroyAll()：经 db-io-executor 异步落库所有注册模型
       redisStore.deletePlayerCache(playerId)
  -> sendResponse(LogoutResponse(1003)) -> Actor 停止（Behaviors.stopped）
```

### GM 踢人

```text
POST /admin/gm/kick  (X-GM-Token)
  -> GmController：令牌未启用返回 404；不匹配返回 401；参数非法返回 400
  -> AkkaKit.tell(GMMessage.class, GmKickPlayerRequest(3004), "management")
  -> GMActor.onKickPlayer
       -> Redis 审计写入（RedisStore.writeLog，走 redis-io-executor）
       -> AuditResult 失败（含异常）-> 直接拒绝，不执行踢出（fail-closed）
       -> 成功 -> sendRpc(GmKickPlayerCommand(3005), playerId)
  -> PlayerActor.onGmKickPlayer
       -> sendRpc(DisconnectChannelCommand, pendingChannelId)
       -> beginDestroy()
  -> GatewayControlActor.onDisconnect
       -> ChannelRegistry.remove + endpoint.close -> Actor 停止
```

### Actor 意外停止

`PostStop` 信号触发 `ActorServerSync.persistAllStopped()`：把所有注册模型（如 `PlayerPO`）的完整快照经 `db-io-executor` 异步提交给 `DBSyncEngineRegistry.saveNow`，作为意外停止时的补偿持久化。

## 状态持久化

```text
PlayerActor（每 sync-interval-ms，默认 1000ms）
  -> onFlushDirty
       online 且无在途刷写且存在脏字段才执行
       refreshFlushTimerIfNeeded()：读取 LogicContext.getSyncIntervalMs()，支持热更新
  -> DBSyncEngine.submit(playerId, 完整快照, version)
       缓冲区满 -> 返回 false，脏字段保留，下一周期重试
       成功     -> clearDirtyMatching(已写入的字段)

DBSyncEngine（后台，每 bgsave-seconds，默认 3s）
  -> snapshot(maxDirtyRecords) 过滤终态失败与退避未到期记录
  -> 按 flush concurrency 分批，PlayerMapper.upsertBatch(records)
       返回的成功 ID -> removeIfVersion 移除对应脏记录
       未返回的 ID -> 乐观锁冲突，保留重试
       异常 -> 全部保留，指数退避（初始 retryBackoffMs，上限 60s），
               达到 maxRetryAttempts 后进入 terminalFailures 并记 dbsync.retry.exhausted
```

Spring 容器关闭时，`LogicNodeRuntime` 依次终止 ActorSystem、停止 `LogConsumer`、关闭 I/O 执行器、flush 并停止 DBSync，然后关闭热配置与指标 HTTP 服务；Spring 管理的 Redis 和数据源由容器关闭。先 flush 再 stop 是保证最后一次快照落库的关键。

## 日志事件流

```text
业务代码 -> RedisStore.writeLog -> XADD log:events      （hash 字段含 level/type/message/playerId）
LogConsumer（守护线程 log-consumer，消费者组 db-sync）
  -> XREADGROUP BLOCK 批量读取（batchSize）
  -> LogEventCodec 解码；单条坏消息跳过并写入 log:events:dead-letter
  -> INSERT INTO log_events ... ON CONFLICT (source_stream_id) DO NOTHING
  -> XACK
```

`source_stream_id` 上的唯一索引保证重复消费不会产生重复行。`LogConsumer.LOG_DEAD_LETTER_KEY` 是排查日志丢失的第一现场。

## 协议与 SDK 生成

```text
Java record + @Rpc/@ProtoField、@ProtoRecord 声明类（game-message-action 自身 src/main/java）
  -> xs-rpc:generate（generate-sources，全量模式只扫该模块自身）
       RpcSourceScanner（JavaParser）-> RpcModel（roleClass 由 record 实现的 @Roles 接口推断，handler 元数据为 null）
       RpcArtifactGenerator（Velocity）
         -> src/main/proto/*.proto（含 ActorModels.proto）、rpc_routes.proto、rpc_routes.md、rpc_protocol_validation.txt（含 proto_message_fields 段）
         -> target/generated-sources/rpc：GameRpcMessages / GeneratedRpcCodec
         -> target/generated-sources/rpc：@ProtoRecord 声明类派生的协议记录源码
  -> protobuf-maven-plugin 编译 proto -> com.game.protocol.pb

@ProtoRecord 源 PO（game-node-modules 自身 src/main/java）
  -> xs-rpc:generate（convertersOnly = true，只扫自身）
         -> target/generated-sources/rpc-converters：GeneratedProtoConverters（PO → 协议记录）

Actor 单参数处理方法（game-node-modules 自身 + ../game-message-action/src/main/java）
  -> xs-rpc:generate（handlersOnly = true）
         -> target/generated-sources/rpc-handlers/com/game/logic/management：GeneratedRpcHandlers（rpcNum → 方法名）

scripts/Generate-Sdks.ps1
  -> mvn generate-sources（xs-net-sdk）产出 target/generated-proto
  -> 合并 xs-net-message 的框架 proto
  -> protoc + 语言插件 / 模板复制
       -> target/code/{unity,typescript,cocos,cpp}
```

`xs-rpc:export` 与 `xs-rpc:verify-export` 提供导出目录与生成目录的逐字节一致性检查，供协议快照或外部消费方使用。

## 指标与诊断采集

```text
Gateway -> /actuator/prometheus（Spring Boot Micrometer，job=gateway，target 8080）
Login   -> /actuator/prometheus（job=login，target 8081）
Logic   -> LogicPrometheusServer /metrics（job=logic，target 9090）

GM 追踪：POST http://127.0.0.1:9090/admin/gm/trace  { protocolNumber, timeoutMs }
  -> GmProtocolTraceService + ProtocolTraceTargetResolver
  -> ProcessArthasTraceClient（Arthas telnet 仅回环 + 令牌保护）
  -> 返回追踪到的 Actor、handler、Arthas 命令与原始 trace 输出
```

`deploy/observability/alerts.yml` 已经定义了三条初始告警：`dbsync_flush_failures_total` 增长、`dbsync_dirty_size > 10000`、Gateway 抓取目标不可达。
