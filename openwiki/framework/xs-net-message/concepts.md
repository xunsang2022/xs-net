---
type: Domain Concepts
title: 领域概念
description: RPC 契约、消息方向与可见性、角色与分片、路由与身份、请求上下文、会话与通道、玩家状态模型、投递语义与有界性。
resource: xs-net/xs-net-message/src/main/java/com/xs/net/message/RpcMessageSpec.java
tags: [xs-net, concepts, rpc, routing]
---
# 领域概念

## RPC 契约模型

一条 RPC 协议在运行时由 `RpcMessageSpec<T extends MessageLite>` 描述，它是不可变 record：

| 字段 | 含义 |
| --- | --- |
| `rpcNum` | 协议编号，必须非负；注册表按它索引 |
| `messageClass` | 对应的 Protobuf 生成类型 |
| `parser` | Protobuf `Parser<T>`，用于解析 `NetMessage.data` |
| `dtoClass` | 业务层 record 类型（`@ProtoField` 声明的那一个） |
| `roleClass` | 目标角色的 `@Roles` 接口，为 `null` 表示不可路由；生成期由 record 实现的 `@Roles` 接口推断 |
| `direction` | `INTERNAL` / `HTTP_RESPONSE` |
| `publicVisible` | 是否允许来自客户端（后续可做网关侧的白名单校验） |
| `playerRoutingRequired` | 是否必须从载荷提取 `playerId` 才能路由 |
| `channelRoutingRequired` | 是否必须携带 `channelId` 才能下行 |
| `codec` | `RpcPayloadCodec<DTO, Proto>`，DTO 与 protobuf 的双向转换 |
| `handler` | `RpcHandlerMetadata`，记录 Actor 类型、组件字段、方法名、请求/响应类型；**全量生成时恒为 `null`**（方法名改由业务模块 `handlersOnly` 生成的 `GeneratedRpcHandlers` 提供） |

`decode` / `encode` 在缺少 codec 或类型不符时抛异常，不做静默降级。

`RpcMessageRegistry` 提供按编号、Protobuf 类型、DTO 类型、角色四种检索，以及 `findResponseForRequest`（通过 handler 的 `responseType` 反查响应规格）。`RpcMessageRegistryBuilder` 在构建时对编号、Protobuf 类型、DTO 类型分别做唯一性校验，重复即抛 `IllegalArgumentException`。

`RpcRouteRegistry` 在消息注册表之上派生 `RpcRouteDescriptor`，供网关使用；它同样拒绝重复路由，并提供 `find(rpcNum)`。

## 方向与可见性

### `RpcMessageDirection`

| 方向 | 语义 | 来源 |
| --- | --- | --- |
| `INTERNAL` | 节点间内部指令，不来自客户端 | `@Rpc(kind = INTERNAL, number)` |
| `HTTP_RESPONSE` | scanner 对 `kind = MESSAGE` 的分类结果，表示"单条单向消息" | `@Rpc(kind = MESSAGE, number)` |
| `ACTOR_MODEL` | scanner 对 `@ProtoRecord` 派生记录的分类结果，表示"可编解码传输的 Actor 模型数据" | `@ProtoRecord(classType, number)` 自动生成 |

注意 `HTTP_RESPONSE` 这个名字容易误解：对 `kind = MESSAGE` 的消息（例如 `LoginRequest` 1000、`LoginResponse` 1002、`LogReportRequest` 2100），scanner 只是把它们标记为无 handler 的单向消息，与 HTTP 传输无关。

`ACTOR_MODEL` 是注解层的类型（`Rpc.Kind`），在 `RpcMessageDirection` 里目前同样落到 `HTTP_RESPONSE`（`publicVisible = true`），与 `MESSAGE` 同一条投递路径；它表达的是"这条规格的协议记录源自某个 Actor 模型 PO"，而不是另一种传输方向。

### `RpcDeliveryClass`

`RpcRouteDescriptor.from` 把方向映射为投递语义：

| 方向 | `RpcDeliveryClass` | 含义 |
| --- | --- | --- |
| `INTERNAL` | `STATE_LATEST` | 状态类消息，可丢弃旧的 |
| `HTTP_RESPONSE` | `REALTIME` | 实时消息 |

`DURABLE_EVENT` 已定义但当前没有生成路径。这些分类目前只作为元数据存在，尚未驱动网关的缓存或合并策略。

### `publicVisible`

scanner 对所有 `kind = MESSAGE` 的消息标记 `publicVisible = true`，对 `kind = INTERNAL` 标记 `false`。当前该值只被写入路由表与启动日志（`GameGatewayApplication` 会打印每条路由的 `public=`），**尚未在网关做强制拦截**，因此 `publicVisible = false` 的规格并不会自动拒绝来自客户端的帧。如果要真正保护内部协议，需要先补上校验逻辑。

## 协议声明与生成

业务协议只有两种声明来源，均由 `xs-net-rpc-maven-plugin` 的 `generate` goal 在 `generate-sources` 阶段扫描：一是 `@Rpc` + `@ProtoField` 手写的 Java record（主路径），二是 `@ProtoRecord` 标注的声明类（由字段级 `@ProtoField` 派生协议记录，见本节末尾）。全量生成**只扫描 `game-message-action` 自身** `src/main/java`，不再扫描业务模块源码：`roleClass` 由 record 实现的 `@Roles` 接口推断，handler 元数据不内联（一律 `null`）。手写 record 的形态：

```java
@Rpc(kind = Rpc.Kind.MESSAGE, number = 1001)
public record LogoutRequest(
        @ProtoField(number = 1) String playerId,
        @ProtoField(number = 2) String sessionId
) implements PlayerMessage, PlayerRoutable, ChannelRoutable {
    @Override
    public String channelId() {
        return sessionId;
    }
}
```

生成规则与硬性约束：

- 每个 record 组件必须有 `@ProtoField(number)`，编号从 1 开始且在同一消息内唯一；没有 `@ProtoField` 或缺字段直接构建失败。
- `@ProtoField` 的 `@Target` 同时允许 `RECORD_COMPONENT` 与 `FIELD`：前者用于协议 record 组件，后者用于 `@ProtoRecord` 标注的 PO 字段。
- 支持的 Java 类型只有 `String` / `Integer`·`int` / `Long`·`long` / `Boolean`·`boolean` / `Double`·`double` / `Float`·`float` / `byte[]`；泛型与数组被拒绝。
- `kind = MESSAGE` 的规格不要求处理方法，生成期也不扫描 Actor，`roleClass` 完全由 record 实现的 `@Roles` 接口推断（如 `S2C`），使非宿主节点也创建对应代理分片；是否需要运行时处理方法由业务语义决定，插件不再校验（方法名改由业务模块 `handlersOnly` 采集进 `GeneratedRpcHandlers`）。
- `kind = INTERNAL` 的 DTO 必须实现某个已注册的 `@Roles` 接口，否则构建失败；它同样不要求处理方法，且不生成响应规格。
- 全局校验：RPC 编号唯一、消息名唯一、字段编号合法。
- 消息名派生：`*Request` → `CS_<Base>`，`*Response` → `SC_<Base>`，其他保持类名。
- proto 文件按角色分组：实现角色接口的进入 `<RoleSimpleName>s.proto`，`kind = ACTOR_MODEL` 的记录进入 `ActorModels.proto`，其余进入 `comment.proto`。
- 一个消息字段引用到其他 proto 文件中的消息类型时，scanner 会在该 `.proto` 顶部补上 `import "<file>.proto";`。

### `@ProtoRecord` 派生协议记录与转换器

`@ProtoField` 也允许出现在类字段上，由 `@ProtoRecord` 触发协议记录生成，用来避免手写与 PO 重复的字段表。改造后**声明与转换分处两个模块**：

- **协议记录声明**在 `game-message-action`（全量生成只扫该模块自身），用一个字段表声明类承载，照 `PlayerInfoProto` 的写法：

```java
@ProtoRecord(classType = "com.game.message.s2c.PlayerInfoData", number = 2102)
public class PlayerInfoProto {           // 仅声明字段，无业务逻辑
    @ProtoField(number = 1) private Long id;
    @ProtoField(number = 2) private String name;
    // ... 字段与 node-modules 的 PlayerPO 一一对应；未在协议中的字段不列
}
```

- **转换器**在 `game-node-modules`：源 PO（`PlayerPO` extends `GameModel`）保留同名 `@ProtoRecord` 与字段级 `@ProtoField`，由 node-modules 自身的 `convertersOnly` 执行（只扫本模块）生成 PO → 协议记录的转换代码：

```java
@ProtoRecord(classType = "com.game.message.s2c.PlayerInfoData", number = 2102)
public class PlayerPO extends GameModel<PlayerPO> {
    @ProtoField(number = 1) private Long id;
    @ProtoField(number = 2) private String name;
    // ... 未标注 @ProtoField 的字段（如 expectedVersion）不进入协议
}
```

约束与行为：

- `classType` 是**全限定类名字符串**（不是 `Class` 字面量），因此被引用的协议记录不存在也不会导致编译失败；若源码里已存在同名 record，则直接复用、不生成。
- `number` 是这条 ACTOR_MODEL 规格的 RPC 编号，必须全局唯一。
- 生成内容：message-action 侧产出 `classType` 指定的协议记录源码（带 `@Rpc(kind = Rpc.Kind.ACTOR_MODEL, number)` 与逐字段 `@ProtoField`）与对应 proto 消息；node-modules 侧单独产出 PO → 协议记录的转换方法。两侧字段表必须保持一致。
- `Timestamp` 字段在生成记录中映射为 `Long`（Unix 毫秒），转换代码调用 `getTime()`；其他字段要求源/目标类型一致，否则构建失败。
- 其他消息引用这条记录时，字段按**消息类型**引用并复用其 codec；只有非消息的普通 record 才会走 `Nested*` 嵌套定义。

### 模型转换入口 `toVo()`

转换方法不只给生成器内部使用，还会注册到框架，业务侧直接调用模型的 `toVo()`：

```java
services.sendRpc(new LoginResponse(0, null, playerPO.toVo()), channelId);
```

- `XsModel#toVo()` 是泛型默认方法（`<V> V toVo()`），按调用处的目标类型推断，例如 `PlayerInfoData info = playerPO.toVo();`；类型不匹配会在调用点抛 `ClassCastException`。
- 内部委托 `XsModelSupport#toVo` → `com.xs.kernel.model.VoConverters`（`xs-net-kernel`），按**源模型类型**查注册表。
- 注册由生成的 `GeneratedProtoConverters` 完成：它在静态初始化中调用 `VoConverters.register(...)`，因此加载该类即完成注册。
- 已注册但未加载时，框架首次转换会按约定类名加载一次转换器持有类（默认 `com.game.message.convert.GeneratedProtoConverters`，可用系统属性 `xs.model.converter-class` 覆盖）；找不到或未注册则抛 `IllegalStateException`，不做静默降级。


生成产物：`GameRpcMessages`（注册表 + `ROUTES`，handler 元数据为 `null`）、`GeneratedRpcCodec`（逐消息的 DTO⇄protobuf 编解码），二者由 message-action 全量生成；`GeneratedProtoConverters`（PO → 协议记录的转换方法，仅 node-modules 的 `convertersOnly = true` 执行生成）；`GeneratedRpcHandlers`（`rpcNum → 方法名` 映射，仅 node-modules 的 `handlersOnly = true` 执行生成，包名 `com.game.logic.management`），以及 `src/main/proto/` 下的 `.proto`、`rpc_routes.md`、`rpc_routes.proto`、`rpc_protocol_validation.txt`。`@ProtoRecord` 声明类派生的协议记录源码写入 message-action 的生成源码目录（`com/game/message/<pkg>/<Record>.java`），与手写 record 一起编译。

## 角色与分片

角色是一个标注了 `@Roles("name")` 的接口，同时充当三个用途：Actor 的消息基类型、`RpcMessageSpec.roleClass`、Cluster Sharding 的实体类型键来源。

| 角色接口 | 名称 | 宿主节点 |
| --- | --- | --- |
| `PlayerMessage` | `logic` | Logic |
| `GMMessage` | `logic` | Logic |
| `BattleMessage` | `battle` | Battle |
| `S2C` | `gateway` | Gateway |
| `GatewayControlMessage` | `gateway` | Gateway |

`AkkaKit.entityTypeKey(actorCmdClass)` 用**角色接口类的简单类名**作为 `EntityTypeKey`（例如 `PlayerMessage`），因此一个角色下的所有 DTO 共享同一个分片类型；实体 ID 由调用方给出（`playerId`、`battleId`、`channelId`）。

节点启动时 `NodeActor` 做两件事：

1. 扫描包下的具体 `AbstractBehavior` 子类，找到消息接口的 `@Roles`；角色命中本节点则 `initClusterSharding` 注册**本地实体**（用反射调用 `create()` 或 `create(String)`）；
2. 遍历注册表中所有 `roleClass`，对不属于本节点的角色调用 `entityRefForClusterSharding` 注册**代理分片**——一个忽略所有消息的空 Behavior，实际投递会被 Pekko 转发到持有该实体的远端节点。

调度器由 `@ActorDispatcher` 指定，默认 `pekko.actor.xs-entity-dispatcher`（256 线程）。

## 路由与身份

上行路由的目标是"哪个玩家实体"。`AkkaKit.tellToRole` 的校验顺序是：

1. 按消息类型找到规格（`NetMessage` 按 `rpcNum`，`RpcMessage` 按 `rpcNum()`，其余按 DTO 类型）；找不到则 `unknown_rpc`；
2. 目标角色必须与规格的 `roleClass` 一致，否则 `role_mismatch`；
3. `RpcMessage` 的载荷类型必须匹配 `messageClass`，否则 `payload_type_mismatch`；
4. 查 `RpcDeliveryInterceptor`，命中且返回 `true` 则直接返回；
5. 否则把消息统一转成 `NetMessage`，应用 `RequestContext`，通过 `entityRef.tell` 投递。

前三种拒绝都会自增 `xs.rpc.routing.rejected{reason}` 计数器并打印 WARN。

Actor 内发送方向的入口 `ActorServices#sendRpc(message, targetEntityId)` 与 `RpcActor#sendRpc(message, targetEntityId)` 不需要显式传目标角色：目标角色由消息类型实现的 `@Roles` 接口解析（同一消息类型必须恰好实现一个 `@Roles` 接口，否则抛 `IllegalArgumentException`），结果按消息类型缓存；解析出的角色随后仍要过上面第 2 步的一致性校验。

身份字段的来源：

| 字段 | 上行来源 | 下行用途 |
| --- | --- | --- |
| `playerId` | DTO 中的 `playerId`（或 `player_id`），由 `RouteValueExtractors.playerId()` 反射读取 | 决定 Logic 中的实体 ID |
| `channelId` | JWT 的 `cid` claim，认证后由 `RequestContext.withIdentity` 覆盖 | 决定 Gateway 中 `ChannelActor` 的实体 ID |

`RouteValueExtractors.channelId()` 会先找 `channelId()`，找不到再退到 `sessionId()`。但网关在认证后会用认证上下文里的 `channelId` 覆盖 DTO 里的值（`GatewayMessageRouter.authenticatedContext`），这是防止客户端伪造下行目标的刻意设计，不要改成优先取 DTO 值。

`TransportKind`（`TCP` / `UDP` / `WEBSOCKET`）出现在 `RpcRouteDescriptor.transports` 中，当前 `RpcRouteDescriptor.from` 恒为"全部三种"，尚无按传输过滤的实现。

## 请求上下文

`RequestContext` 是不可变 record，随请求在网关、Actor 与跨节点调用之间传播：

| 字段 | 说明 |
| --- | --- |
| `traceId` | 缺失或空白时自动生成 UUID；写入 MDC 供日志关联 |
| `deadlineEpochMs` | 截止时间；`expired()` 在到期后返回 true，Actor 会丢弃信封 |
| `hopCount` | 每经过一次 `nextHop` 自增 |
| `playerId` / `channelId` | 身份 |
| `sourceNode` | 源节点标识 |
| `schemaVersion` | 上下文协议版本，当前 `CURRENT_SCHEMA_VERSION = 1` |

`RequestContext.from(NetMessage)` 兼容旧字段：`playerId` 为空时回退到 `user_id`，`channelId` 为空时回退到 `session_id`，`deadlineEpochMs` 为空时按 `LEGACY_DEFAULT_TIMEOUT_MS = 3000` 兜底。

`RequestContextHolder` 是线程级载体：`call(context, action)` 进入时写入 `ThreadLocal` 与 MDC（`traceId`、`playerId`、`channelId`、`sourceNode`），退出时恢复原值，避免线程池复用导致 traceId 串线。`RpcActor` 在解码 `NetMessage` 时把上下文存入 `deliveryContexts`（以消息对象为键的 `IdentityHashMap`），在 `contextual(...)` 包装的处理器里取出并恢复——这就是 Actor 内 `currentRequestContext()` 能拿到上下文的机制。

## 会话与通道

`channelId` 是"一个客户端连接/会话"的标识，不是用户标识：同一玩家重新连接会得到新的 `channelId`（除非客户端复用 `X-Channel-Id`）。

- Login `/api/auth/issue`：请求头 `X-Channel-Id` 缺省时生成 UUID，作为 JWT 的 `cid`；该端点按客户端 IP 做固定窗口限流（`ClientIpFixedWindowRateLimiter`），超限返回 429；
- Gateway 认证成功后把 `channelId -> ClientEndpoint` 写入 `ChannelRegistry`；
- `ClientEndpoint` 抽象统一 TCP Channel、UDP 会话、KCP 会话与 WebSocket 通道，`write(NetMessage)` 负责各自的编码与发送；
- `S2C` 消息经 `ChannelActor` 处理：找到 endpoint、检查 `isActive()`、写入 `NetMessage`，失败计入 `gateway.s2c.dropped`；
- `max-connections-per-account`（默认 1）在认证时拒绝同一 `channelId` 的重复活跃连接。

`ChannelRegistry` 同时注册 `gateway.online.connections` 指标，并提供登录往返计时用的 `recordLoginIngress` / `takeLoginIngress`；入口时间戳由 `AuthSupport.markLoginIngress` 在 TCP/UDP/KCP 认证成功时写入，`ChannelActor` 写回首个 S2C 时用 `takeLoginIngress` 取出并记录 `gateway.login.roundtrip`。

## 玩家状态模型

`PlayerPO` 对应 Redis hash `player:{playerId}` 和 PostgreSQL `users` 行，持久化字段为 `id`、`name`、`email`、`online`、`last_login_at`、`updated_at`、`version`；`expectedVersion` 是仅供写入时使用的乐观锁字段，不映射到表或 Redis。`PlayerPO` 继承 `GameModel` 并实现 xs-net 的 `XsModel` 契约，`setProperty` 根据 setter 方法引用定位字段、记录脏字段；未知或非持久化字段会被拒绝。Redis Hash 的字段名通过模型支持将 camelCase 转为 snake_case。

`PlayerPO` 通过 `@ProtoRecord` 与逐字段 `@ProtoField` 参与对外协议（见上一节 `@ProtoRecord` 派生协议记录与转换器），但**只用于在 node-modules 生成 PO → 协议记录的转换器**；真正的 `com.game.message.s2c.PlayerInfoData`（RPC 2102 的 ACTOR_MODEL 记录）由 `game-message-action` 的 `PlayerInfoProto` 声明生成。登录成功时调用 `data.toVo()` 转换后放进 `LoginResponse.playerInfo` 下发。`expectedVersion` 既不落库也不上协议。修改 `PlayerPO` 字段时，必须同时评估表结构、Redis Hash、`PlayerInfoProto` 的字段/编号与协议三处影响。

玩家加载与在线状态（`PlayerInfo`）：

- 玩家数据在 `PlayerActor` 构造期由 `PlayerInfo.loadingModel` **同步**加载：先读 Redis 缓存 `player:{playerId}`，未命中再经 `DBSyncEngineRegistry.loadOrCreate` 读数据库并回写 Redis；没有阶段机、加载超时定时器与消息暂存；
- `onLoginMessage` 处理 `LoginRequest`：置 `online=true`，写 `online`/`last_login_at`/`updated_at` 与 `version+1` 脏字段，保存 Redis 并启动脏数据刷新定时器，随后回发 `LoginResponse`；
- 登出（`LogoutRequest`）或 GM 踢出：置 `online=false`、取消刷新定时器、写离线状态并调用 `ActorServerSync.destroyAll()` 持久化，随后 Actor 停止；
- `PostStop` 时 `ActorServerSync.persistAllStopped()` 在 `db-io-executor` 上补偿持久化所有注册模型。

`version` 是乐观锁：每次状态变更自增。DBSync 通过 `PlayerMapper.upsertBatch` 执行 PostgreSQL UPSERT，并用 `expectedVersion` 判断版本是否匹配；未返回成功 ID 的记录保留到下一轮重试。`clearDirtyMatching` 只清除"值仍等于已持久化内容"的字段，避免异步写期间的新修改被误清。

## 投递语义与有界性

这套系统刻意选择"有界 + 丢弃 + 可观测"，而不是无限缓冲或可靠投递：

| 位置 | 上限 | 超限行为 |
| --- | --- | --- |
| 网关全局准入 | 4096 在途 | 拒绝该消息，`gateway.ingress.rejected{reason=capacity}` |
| 网关单连接准入 | 256 在途 | 同上 |
| 网关过期请求 | `deadlineEpochMs` | 拒绝，`reason=expired` |
| DBSync 脏缓冲 | `maxDirtyRecords` / `maxDirtyBytes` | `submit` 返回 false，状态不落库 |
| DBSync 重试 | `maxRetryAttempts` | 进入 `terminalFailures`，不再重试并记 `dbsync.retry.exhausted` |
| 日志消费 | 死信 key `log:events:dead-letter` | 坏消息跳过整批，写入死信 |
| Actor 信封解码失败 | — | 丢弃并记 `xs.rpc.actor.envelope.dropped{reason}` |

没有任何一层提供端到端的投递确认或重放：`entityRef.tell` 是 at-most-once。需要可靠性的场景必须由业务自己在状态层做补偿（例如玩家状态以最终一次快照 + 乐观锁收敛）。
