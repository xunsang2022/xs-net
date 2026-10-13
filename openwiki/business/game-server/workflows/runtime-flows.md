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

`application.yml` 通过 `spring.autoconfigure.exclude` 排除 `GameFrameworkAutoConfiguration`，因此不创建 ActorSystem。启动只装配 `JwtUtil` 与 `AuthController`。

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
  -> jwtUtil.validateToken -> Claims
  -> channelId = claims.cid，缺失即失败
  -> max-connections-per-account 检查（ChannelRegistry.isActive）
  -> ChannelRegistry.register(channelId, TcpClientEndpoint)
  -> gateway.auth.success / gateway.auth.failure / gateway.connection.rejected

后续帧：XS packet(packetId=DATA)，body = NetMessage Protobuf 字节 -> GatewayMessageRouter.route(body, channelId)
```

注意：由于拆帧在 `AuthHandler` 之前，**JWT 帧同样使用 XS packet，前 4 字节是 body 长度字段**，JWT 位于 `HELLO` 包的 body 中。实现与 SDK 都遵循完整 XS packet 格式，而不是简单的"长度前缀 + NetMessage"。

连接断开时 `channelInactive` 从 `ChannelRegistry` 移除并清理认证超时任务。

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
  -> 分支：
       LoginRequest  -> ChannelRegistry.recordLoginIngress(channelId, nanoTime)
                        构造 PlayerEnterCommand(playerId, playerName, channelId)
                        AkkaKit.tell(..., PlayerMessage.class, command, playerId, context)
       LogoutRequest -> 用认证 channelId 重建 LogoutRequest 再投递
       其他           -> 直接投递该 DTO
```

关键设计：**客户端协议 1000（`LoginRequest`）不会原样进入 Logic**，网关把它转换成内部协议 3003（`PlayerEnterCommand`）再投递，这就是 GM 追踪接口在追踪登录时必须填写 3003 的原因。

投递到 Pekko 后的路径见 [领域概念](../../framework/xs-net-message/concepts.md#路由与身份)：校验 → 拦截器 → `entityRef.tell(NetMessage)`；跨节点由 Artery 经 `NetMessageSerializer` 传输，远端 `RpcActor.onNetMessage` 解码、恢复上下文并 `tell` 给自己。

## 登录加载与首次响应

`PlayerActor` 收到 3003 后的完整流程：

```text
PlayerEnterCommand
  -> lifecycle.onPlayerEnter
       phase == NEW -> beginLoading（设 id/name、phase=LOADING）
                       startSingleTimer(LOAD_TIMEOUT_KEY, 3000ms)
                       stateSync.loadFromCache(playerId)       [redis-io-executor]
       phase == ONLINE -> applyEnter：更新 name（若变化则标脏）→ markOnlineFields → 发 SC_Login
       其他            -> 记录 WARN 后忽略

CacheLoadResult（命中且非空）
  -> writeInitialState -> prepareOnlineState（fromHash + 补齐 id/name + markOnlineFields + version+1）
  -> stateSync.writeInitialState(...)                      [redis-io-executor]

CacheLoadResult（未命中或读失败）
  -> stateSync.loadFromDatabase(playerId)                   [db-io-executor]
       DBSyncEngine.loadOrCreate：INSERT ... ON CONFLICT ... RETURNING ...

DatabaseLoadResult（为空或出错）
  -> cancel 超时定时器 -> failLoading -> stash.clear -> Actor 停止

InitialRedisWriteResult（成功）
  -> cancel 超时定时器 -> clearDirtyMatching -> markOnline
  -> stateSync.startFlushTimer()
  -> onLoginMessage 内联发送：data.toVo() 转出协议记录
                        LoginResponse(result, token, playerInfo) -> S2C
                        -> sendRpc(resp, pendingChannelId)
  -> recordLoginLogicTotal
  -> stash.unstashAll(Behaviors.same())

LoadTimeout（加载超过 3 秒）
  -> failLoading -> stash.clear -> Actor 停止
```

缓存命中与未命中分别计入 `login.redis.load.duration{cache=hit|miss|failure}`、`login.database.load.duration`、`login.redis.create.duration`、`login.logic.total.duration`，以及 `login.s2c.send.nano` 时间戳。

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
LogoutRequest(1001) -> PlayerActor -> lifecycle.onLogoutMessage
  -> 记录 pendingLogout，beginDestroy()
       -> beginDestroyLifecycle（要求 ONLINE）：phase=DESTROYING，online=false，标记 online/updated_at 脏
       -> cancelFlushTimer
       -> stateSync.destroy(完整快照, version)            [redis-io-executor]
  -> DestroyResult
       -> completeDestroy：写 Redis 成功且 DBSync 接收成功才算完成
       -> 发送 LogoutResponse(1003) -> Actor 停止
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

`PostStop` 时若 `shouldPersistOnStop()`（`ONLINE` 或 `DESTROYING`）为真，`persistStopped` 会在 redis-io-executor 上补写 Redis，并在成功后把完整快照提交给 `DBSyncEngine`。

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
Java record + @Rpc/@ProtoField（三个 sourceDirectories）
  -> xs-rpc:generate（generate-sources）
       RpcSourceScanner（JavaParser）-> RpcModel
       RpcArtifactGenerator（Velocity）
         -> src/main/proto/*.proto（含 ActorModels.proto）、rpc_routes.proto、rpc_routes.md、rpc_protocol_validation.txt
         -> target/generated-sources/rpc：GameRpcMessages / GeneratedRpcCodec / GeneratedRpcHandlers
         -> target/generated-sources/rpc：@ProtoRecord 派生的协议记录源码
  -> protobuf-maven-plugin 编译 proto -> com.game.protocol.pb

@ProtoRecord(classType, number) 标注的 PO（game-node-modules）
  -> xs-rpc:generate（convertersOnly = true，game-node-modules 内）
         -> target/generated-sources/rpc-converters：GeneratedProtoConverters（PO → 协议记录）

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
