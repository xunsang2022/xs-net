---
type: Source Map
title: 模块与源码地图
description: xs-net 两个 reactor 各模块的职责、关键入口、生成源、测试分布与跨模块修改热区。
resource: xs-net/pom.xml
tags: [xs-net, modules, source-map, maven]
---
# 模块与源码地图

## 构建坐标

| 坐标 | 版本 | 说明 |
| --- | --- | --- |
| `com.xs:xs-net-workspace` | `0.0.1-SNAPSHOT` | 根 reactor（`pom`），聚合 `xs-net` 与 `game-server` |
| `com.xs:xs-net` | `0.0.1` | 框架 reactor（`pom`），7 个模块 |
| `com.xs:xs-net-all` | `0.0.1` | 框架聚合包，导出 `xs-net-kernel` + `xs-net-transport` |
| `com.xs:game-server` | `0.0.1-SNAPSHOT` | 业务 reactor，父 POM 为 `spring-boot-starter-parent:4.0.0`，6 个模块 |

依赖版本集中在 `xs-net/pom.xml` 的 `dependencyManagement`：Pekko BOM `1.1.5`、Aeron driver `1.40.0`、LMDB `0.8.3`、Lombok `1.18.34`、Micrometer `1.16.0`、Spring `7.0.1`。`game-server/pom.xml` 另行管理 Protobuf `3.25.5`、jjwt `0.12.6`、Netty `4.1.115.Final` 与 Spring Boot `4.0.0`。Java 版本为 21。

## 框架模块

| 模块 | 职责 | 关键源码 |
| --- | --- | --- |
| `xs-net-tools` | 工具聚合：`tool-commons-lang4`（`ArrayKit`/`ClassScanner`/`FileKit`/`FormatTimeKit`/`StrKit`）与 `tool-luBan-cfg`（LuBan 配置加载） | `xs-net/xs-net-tools/` |
| `xs-net-message` | `NetMessage` proto 与生成类型、`RpcMessageSpec`、`RpcMessageRegistry`(+Builder)、`RpcRouteDescriptor`/`RpcRouteRegistry`、`MessageAble`、`RpcRuntime`、`RequestContext`/`RequestContextHolder`、`RouteValueExtractors`、`NetMessageSerializer` | `xs-net/xs-net-message/src/main/java/com/xs/net/message/` |
| `xs-net-kernel` | 模型契约与 Actor 运行时：`XsModel`/`XsModelSupport`（属性读写、脏字段、Hash 转换、`toVo()`）、`VoConverters`（模型 → 协议记录转换注册表）、`RedisKey`、`AkkaKit`（分片初始化、投递、投递拦截器）、`RpcActor`/`ActorServices`/`SimpleActorServer`、`NodeActor`（角色扫描与分片注册）、`Roles`/`ActorDispatcher`、`GameFrameworkAutoConfiguration` 与 `SpringUtil`/`ClassScanner` | `xs-net/xs-net-kernel/src/main/java/com/xs/kernel/` |
| `xs-net-transport` | 传输抽象：`FrameCodec`、`RawFrameCodec`、`TcpFrameCodec`、`TransportAdapter`、`TransportSession`、`TransportMessageHandler`、`TransportCapabilities`、`OutboundMessage`、`BackpressureDecision`、`IngressAdmissionController` | `xs-net/xs-net-transport/src/main/java/com/xs/net/transport/` |
| `xs-net-rpc-maven-plugin` | `generate`：扫描源目录生成 proto/注册表/路由表，另有 `convertersOnly`（只生成 PO → 协议记录转换器）与 `handlersOnly`（只生成 `rpcNum → 方法名` 映射）两种精简模式；`export` / `verify-export`：导出一致性校验；`export-typescript-sdk` / `export-unity-sdk` / `export-cpp-sdk`：SDK 导出 | `xs-net/xs-net-rpc-maven-plugin/src/main/java/com/xs/net/rpc/maven/` |
| `xs-net-sdk` | SDK 生成模块（`pom` 打包）。绑定 `generate` + protoc 编译，模板位于 `src/main/sdk/{unity,typescript,cocos,cpp}` | `xs-net/xs-net-sdk/pom.xml` |
| `xs-net-all` | 聚合依赖，供业务侧单坐标引入 | `xs-net/xs-net-all/pom.xml` |

## 业务模块

| 模块 | 启动方式 | 职责 | 关键源码 |
| --- | --- | --- | --- |
| `game-comment` | 库 | `JwtUtil`（HS256，`cid` claim）、`CommentConfig` | `game-server/game-comment/src/main/java/com/game/comment/` |
| `game-message-action` | 库 | 业务 DTO record、`@Rpc`/`@ProtoField`、`@ProtoRecord` 声明类（`PlayerInfoProto`）、`PlayerRoutable`/`ChannelRoutable`、角色接口（`PlayerMessage`/`BattleMessage`/`GMMessage`/`S2C`/`GatewayControlMessage`）、`GameRpcConfig`、`.proto` 与 `rpc_routes.md` | `game-server/game-message-action/src/main/` |
| `game-node-gateway` | Spring Boot | 四协议接入与认证、`GatewayMessageRouter`、`ChannelRegistry`、`ChannelActor`/`GatewayControlActor`、`GmController` | `game-server/game-node-gateway/src/main/java/com/game/gateway/` |
| `game-node-login` | Spring Boot | `AuthController`（`/api/auth/issue`、`/api/auth/verify`）、`ClientIpFixedWindowRateLimiter`（issue 按客户端 IP 固定窗口限流） | `game-server/game-node-login/src/main/java/com/game/login/` |
| `game-node-modules` | Spring Boot 非 Web：`com.game.GameLogicApplication`；运行时：`LogicNodeRuntime` | `PlayerActor`/`PlayerInfo`/`ActorServerSync`、`PlayerPO`/`GameModel`、`GMActor`、Redis/MyBatis-Plus/DBSync、`LogConsumer`、`LogicPrometheusServer`、Arthas 追踪 | `game-server/game-node-modules/src/main/java/com/game/logic/` |
| `game-node-battle` | `com.game.battle.GameBattleApplication` | `BattleActor`、`BattleCommandIngress`、`BattleExecutorPool`、`BattleRuntime`、`BattleState` | `game-server/game-node-battle/src/main/java/com/game/battle/` |

## 启动与投递入口

- 网关启动：`game-server/game-node-gateway/src/main/java/com/game/GameGatewayApplication.java`
- 网关 TCP 装配与认证：`.../gateway/netty/NettyChannelInitializer.java`、`.../gateway/netty/AuthHandler.java`；TCP/UDP/KCP 共享的认证公共流程与指标名常量：`.../gateway/netty/AuthSupport.java`
- 网关上行路由：`.../gateway/netty/GatewayMessageRouter.java`
- 网关 UDP/KCP/WebSocket：`.../gateway/netty/UdpServer.java`、`.../gateway/netty/WebSocketServer.java`、`.../gateway/kcp/KcpServer.java`
- 网关下行写回：`.../gateway/actor/ChannelActor.java`、`.../gateway/netty/ChannelRegistry.java`
- 登录服务：`game-server/game-node-login/src/main/java/com/game/login/controller/AuthController.java`
- Logic 启动入口：`game-server/game-node-modules/src/main/java/com/game/GameLogicApplication.java`
- Logic Bean 装配与属性绑定：`.../logic/config/LogicNodeConfiguration.java`、`.../logic/config/LogicNodeProperties.java`
- Logic 运行时生命周期：`.../logic/config/LogicNodeRuntime.java`
- 玩家模型与 Redis Hash 映射：`.../logic/actor/player/PlayerPO.java`、`.../logic/dbsync/GameModel.java`、`.../logic/redis/XsModelHashMapper.java`
- 玩家业务与生命周期：`.../logic/actor/player/core/PlayerInfo.java`；状态同步：`.../logic/actor/core/ActorServerSync.java`
- 批写与日志消费：`.../logic/dbsync/DBSyncEngine.java`、`.../logic/dbsync/LogConsumer.java`
- 战斗节点：`game-server/game-node-battle/src/main/java/com/game/battle/GameBattleApplication.java`
- 框架投递核心：`xs-net/xs-net-kernel/src/main/java/com/xs/kernel/akka/AkkaKit.java`
- 角色注册：`xs-net/xs-net-kernel/src/main/java/com/xs/kernel/actor/node/NodeActor.java`

## 生成源与生成物

以下位置存在生成内容，修改前必须分清"手写"与"生成"：

| 位置 | 生成方式 | 是否入库 |
| --- | --- | --- |
| `xs-net/xs-net-message/src/main/generated/` | `protobuf-maven-plugin` 编译 `src/main/proto/net_message.proto`，输出到 `src/main/generated`，再由 `build-helper-maven-plugin` 加入源码根 | 是（框架信封类型固定） |
| `game-server/game-message-action/target/generated-sources/rpc/` | message-action 的 `xs-rpc:generate`（全量，只扫描自身 `src/main/java`）Velocity 模板输出：`GameRpcMessages`（handler 元数据为 `null`）、`GeneratedRpcCodec`，以及 `@ProtoRecord` 声明类（如 `PlayerInfoProto`）派生的协议记录源码 | 否 |
| `game-server/game-node-modules/target/generated-sources/rpc-converters/` | node-modules 的 `xs-rpc:generate`（`convertersOnly = true`，只扫描自身）输出：`GeneratedProtoConverters`（PO → 协议记录） | 否 |
| `game-server/game-node-modules/target/generated-sources/rpc-handlers/com/game/logic/management/` | node-modules 的 `xs-rpc:generate`（`handlersOnly = true`，扫描自身 + `../game-message-action/src/main/java`）输出：`GeneratedRpcHandlers`（`rpcNum → 方法名`） | 否 |
| `game-server/game-message-action/src/main/proto/` | `generate` 的 `protoOutputDirectory`，每次构建会删除旧 `.proto` 后重写，并输出 `rpc_routes.md`、`rpc_routes.proto`、`rpc_protocol_validation.txt` | 是 |
| `xs-net/xs-net-sdk/target/code/{unity,typescript,cocos,cpp}` | `scripts/Generate-Sdks.ps1` 驱动的 SDK 生成 | 否 |

`game-message-action/target/generated-sources/protobuf/` 由 `protobuf-maven-plugin` 从 `src/main/proto` 生成，包名为 `com.game.protocol.pb`。

`rpc_protocol_validation.txt` 除 `status` / `rpc_count` / `duplicate_rpc_numbers` / `duplicate_message_names` / `generated_at` 外，还包含 `proto_message_fields:` 段：逐消息（含嵌套消息）按字段号排序列出「字段名=字段号」，作为协议向后兼容基线——提交后 CI 或人审可通过 git diff 发现字段号被复用（同号换字段名）或已有字段被删除。

`generate` goal 的三个精简参数（默认 `false`）决定执行哪条路径：`convertersOnly` 只输出 `GeneratedProtoConverters`；`handlersOnly` 只输出 `GeneratedRpcHandlers`，配合 `handlerJavaPackage`（默认 `com.game.message.rpc`）与 `handlerOutputDirectory`（默认 `target/generated-sources/rpc-handlers`）控制落盘位置。`GeneratedRpcHandlers` 由模板 `xs-net/xs-net-rpc-maven-plugin/src/main/resources/templates/GeneratedRpcHandlersMap.vm` 渲染，暴露 `Map<Integer,String> methodNames()` 与 `String methodName(int)`。

## 测试分布

| 模块 | 测试 |
| --- | --- |
| `xs-net-kernel` | `RpcActorTest`、`AkkaKitRpcRoutingTest`、`ClassUtilTest`、`VoConvertersTest` |
| `xs-net-message` | `MessageAbleTest`、`NetMessageGoldenTest`、`RequestContextTest`、`RpcMessageRegistryBuilderTest`、`RpcRouteRegistryTest` |
| `xs-net-transport` | `TcpFrameCodecTest`（JUnit 5） |
| `xs-net-rpc-maven-plugin` | `RpcSourceScannerTest`、`SdkProtoExporterTest` |
| `game-message-action` | `GameMessagesGoldenTest`、`RpcTransportTest`，配套 `src/test/resources/golden/*.hex` 字节级黄金样本 |
| `game-node-gateway` | `GatewayMessageRouterTest`、`ChannelRegistryTest`、`UdpReliableLayerTest`、`WebSocketGameHandlerTest`、`GmControllerTest`、`ManagementTokenVerifierTest`、`NettyConfigTest`、`WebSocketConfigTest`、`PekkoConfigurationTest`、`KcpConfigTest`、`KcpTest` |
| `game-node-login` | `AuthControllerTest` |
| `game-node-modules` | `LogicContextTest`、`LogicIoExecutorsTest`、`LogicRuntimeConfigurationTest`、`LogicClasspathBoundaryTest`、`LogicPrometheusServerTest`、`RedisStoreTest`、`GmProtocolTraceServiceTest`、`ProtocolTraceTargetResolverTest` |
| `game-event-sourcing` | `DBSyncEngineP0Test`、`DBSyncEngineP1Test`、`DBSyncEngineRegistryTest`、`DirtyStateBufferTest`、`EntityPersistenceRouterTest`、`LogEventCodecTest`、`MybatisPersistenceConfigurationTest` |
| `game-node-battle` | `BattleActorDispatcherTest`、`BattleCommandIngressTest`、`BattleExecutorTest`、`BattleRuntimeTest`、`BattleStateTest`、`BattleLoadTest`、`PekkoConfigurationTest` |

黄金样本（`*.hex`）是线格式的回归基线，改动 `NetMessage` proto 字段或 TCP 分帧时必须一并评估。`LogicClasspathBoundaryTest` 用于约束 Logic 模块的类路径边界，不要在未确认的情况下绕过。

## 跨模块修改热区

### 新增或修改业务协议

1. 在 `game-message-action/src/main/java` 新增 record，加 `@Rpc` 与每个字段的 `@ProtoField`——全量生成只扫描该模块自身，放在 `game-node-modules` / `game-node-battle` 的 record 不会被采集；
2. 对需要 Actor 处理的 `MESSAGE` 消息，在宿主节点的某个 `RpcActor<Role>`（或它持有的组件字段）中写单参数处理方法；其方法名由宿主模块的 `handlersOnly` 执行采集进 `GeneratedRpcHandlers`，**不再回填 `GameRpcMessages`**（全量模式下 handler 元数据为 `null`）；
3. 确认 DTO 实现了对应角色的 `@Roles` 接口（`roleClass` 由此推断；`kind = INTERNAL` 必须实现，否则构建失败）；
4. 重建 `game-message-action`，检查 `src/main/proto/rpc_routes.md` 与 `rpc_protocol_validation.txt`；如需诊断追踪方法名再重建对应业务模块；
5. 需要客户端联调时运行 `scripts/Generate-Sdks.ps1`。

### 新增或修改 Actor 模型协议（`@ProtoRecord`）

要让某个 PO 的状态直接进协议（例如登录响应携带玩家数据），不要手写重复的 record：

1. 在 `game-message-action` 新增 `@ProtoRecord(classType = "<全限定记录类名>", number = <RPC 编号>)` 的声明类（照 `PlayerInfoProto` 的写法），给需要上协议的字段加 `@ProtoField(number)`；协议记录由 message-action 自身生成；
2. 若还需要 PO → 协议记录的转换器，在 `game-node-modules` 的源 PO 上加同名 `@ProtoRecord` 与字段级 `@ProtoField`（`convertersOnly` 只扫描 node-modules 自身，据此生成 `GeneratedProtoConverters`）；
3. 在 `game-message-action` 的消息里按 `classType` 的简单类名引用该字段（该记录会由插件生成到生成源码目录，不要在源码里重复声明）；
4. 重建 `game-message-action`（生成协议记录、`ActorModels.proto`、注册表与 codec），再重建 `game-node-modules`（生成 `GeneratedProtoConverters` 并编译 PO 侧调用）；
5. 字段类型变化要注意：`Timestamp` 会映射为 `Long` 毫秒，其他类型必须源/目标一致，否则插件构建失败；
6. 协议编号新增后要同步 `game-message-action` 的注册表断言测试（`GameMessagesGoldenTest#registryIsCompleteAndUnique`）。

### 修改路由或上下文

同时检查 `RpcRouteDescriptor`（方向 → `RpcDeliveryClass` 的映射）、`RouteValueExtractors`（`playerId` / `channelId` 反射提取与缓存）、`GatewayMessageRouter`（准入与投递决策）、`AkkaKit.tellToRole`（角色校验与拒绝指标）、`RequestContext.applyTo`（信封字段回填）。`xs.rpc.routing.rejected` 与 `xs.rpc.actor.envelope.dropped` 两个计数器是这些路径的可观测出口。

### 修改传输接入

连接无关的契约改动放 `xs-net-transport`；TCP/WebSocket/自研 UDP 的认证、XS packet 分帧和会话语义改动放 `game-node-gateway` 的 `netty` 包，KCP 仍由 `kcp` 包维护独立的 KCP 应用层封装。前三类入口维持"首个 `HELLO(JWT)` → 绑定 `channelId` → 后续 `DATA(NetMessage)`"模型；自动重连必须复用同一个 `random`。

### 修改状态持久化

`PlayerPO`/`GameModel`（玩家字段、脏标记与 ORM 映射）、`RedisStore`（key 布局 `player:{id}`、`idx:player`、`player:online`）、`PlayerMapper.xml`（PostgreSQL UPSERT）、`DBSyncEngine`（批大小、重试、乐观锁）、`deploy/pg-init.sql`（表结构与索引）必须一起看。表结构不一致会导致首次写入失败或 flush 重试耗尽。注意 `PlayerPO` 上的 `@ProtoRecord` + 字段级 `@ProtoField` **只驱动 node-modules 的 `convertersOnly` 转换器**；真正生成 `GameRpcMessages` 中 ACTOR_MODEL 规格与 `PlayerInfoData` 的是 `game-message-action` 的 `PlayerInfoProto`。增删字段时三处（PO、`PlayerInfoProto` 的字段表与编号、登录响应）必须同步。

### 修改 SDK 生成

`xs-net-sdk/pom.xml` 只负责 proto 生成；真正的语言导出在插件 Mojo 与 `src/main/sdk/*` 模板中。`ExportRpcMojo` 与 `VerifyExportRpcMojo` 保证导出目录与生成目录逐字节一致，改动生成模板后必须同时验证这组一致性检查。
