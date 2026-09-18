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
| `roleClass` | 目标角色的 `@Roles` 接口，为 `null` 表示不可路由 |
| `direction` | `REQUEST` / `RESPONSE` / `INTERNAL` / `HTTP_RESPONSE` |
| `publicVisible` | 是否允许来自客户端（后续可做网关侧的白名单校验） |
| `playerRoutingRequired` | 是否必须从载荷提取 `playerId` 才能路由 |
| `channelRoutingRequired` | 是否必须携带 `channelId` 才能下行 |
| `codec` | `RpcPayloadCodec<DTO, Proto>`，DTO 与 protobuf 的双向转换 |
| `handler` | `RpcHandlerMetadata`，记录 Actor 类型、组件字段、方法名、请求/响应类型 |

`isRoutable()` 等价于 `roleClass != null`；`hasCodec()` 判断是否配置了 DTO 编解码。`decode` / `encode` 在缺少 codec 或类型不符时抛异常，不做静默降级。

`RpcMessageRegistry` 提供按编号、Protobuf 类型、DTO 类型、角色四种检索，以及 `findResponseForRequest`（通过 handler 的 `responseType` 反查响应规格）。`RpcMessageRegistryBuilder` 在构建时对编号、Protobuf 类型、DTO 类型分别做唯一性校验，重复即抛 `IllegalArgumentException`。

`RpcRouteRegistry` 在消息注册表之上派生 `RpcRouteDescriptor`，供网关使用；它同样拒绝重复路由，并提供 `require(rpcNum)`。

## 方向与可见性

### `RpcMessageDirection`

| 方向 | 语义 | 来源 |
| --- | --- | --- |
| `REQUEST` | 客户端请求，需要 Actor 处理方法与响应 | `@Rpc(kind = REQUEST, requestNumber, responseNumber)` 展开成两条规格 |
| `RESPONSE` | `REQUEST` 的响应规格，不带 handler | 由 `responseNumber` 自动生成 |
| `INTERNAL` | 节点间内部指令，不来自客户端 | `@Rpc(kind = INTERNAL, number)` |
| `HTTP_RESPONSE` | scanner 对 `kind = MESSAGE` 的分类结果，表示"单条单向消息" | `@Rpc(kind = MESSAGE, number)` |

注意 `HTTP_RESPONSE` 这个名字容易误解：对 `kind = MESSAGE` 的消息（例如 `LoginRequest` 1000、`LoginResponse` 1002、`LogReportRequest` 2100），scanner 只是把它们标记为无 handler 的单向消息，与 HTTP 传输无关。

### `RpcDeliveryClass`

`RpcRouteDescriptor.from` 把方向映射为投递语义：

| 方向 | `RpcDeliveryClass` | 含义 |
| --- | --- | --- |
| `RESPONSE` | `RESPONSE` | 会话相关的响应 |
| `INTERNAL` | `STATE_LATEST` | 状态类消息，可丢弃旧的 |
| 其他 | `REALTIME` | 实时消息 |

`DURABLE_EVENT` 已定义但当前没有生成路径。这些分类目前只作为元数据存在，尚未驱动网关的缓存或合并策略。

### `publicVisible`

scanner 对所有 `kind = MESSAGE` 与 `kind = REQUEST` 的消息标记 `publicVisible = true`，对 `kind = INTERNAL` 标记 `false`。当前该值只被写入路由表与启动日志（`GameGatewayApplication` 会打印每条路由的 `public=`），**尚未在网关做强制拦截**，因此 `publicVisible = false` 的规格并不会自动拒绝来自客户端的帧。如果要真正保护内部协议，需要先补上校验逻辑。

## 协议声明与生成

业务协议的唯一来源是 Java record 加注解，由 `xs-net-rpc-maven-plugin` 的 `generate` goal 在 `generate-sources` 阶段扫描：

```java
@Rpc(kind = Rpc.Kind.REQUEST, requestNumber = 1001, responseNumber = 1003)
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
- 支持的 Java 类型只有 `String` / `Integer`·`int` / `Long`·`long` / `Boolean`·`boolean` / `Double`·`double` / `Float`·`float` / `byte[]`；泛型与数组被拒绝。
- `kind = REQUEST` 必须能在扫描到的 `RpcActor<R>`（含其字段组件）里找到"单参数且参数类型为该 DTO"的方法；方法返回类型必须是被扫描到的 record，作为响应 DTO。
- `kind = INTERNAL` 同样要求处理方法，但不生成响应规格。
- `kind = MESSAGE` 不要求 handler；若实现了某个 `@Roles` 接口（如 `S2C`），scanner 会把该角色写入规格，使非宿主节点也创建对应代理分片。
- `REQUEST` / `INTERNAL` 的 DTO 必须实现 handler 所属 Actor 的角色接口，否则构建失败。
- 全局校验：RPC 编号唯一、消息名唯一、字段编号合法。
- 消息名派生：`*Request` → `CS_<Base>`，`*Response` → `SC_<Base>`，其他保持类名。
- proto 文件按角色分组：实现角色接口的进入 `<RoleSimpleName>s.proto`，其余进入 `comment.proto`。

生成产物：`GameRpcMessages`（注册表 + `ROUTES`）、`GeneratedRpcCodec`（逐消息的 DTO⇄protobuf 编解码）、`GeneratedRpcHandlers`（编号 → Actor/方法/请求类型的清单），以及 `src/main/proto/` 下的 `.proto`、`rpc_routes.md`、`rpc_routes.proto`、`rpc_protocol_validation.txt`。

## 角色与分片

角色是一个标注了 `@Roles("name")` 的接口，同时充当三个用途：Actor 的消息基类型、`RpcMessageSpec.roleClass`、Cluster Sharding 的实体类型键来源。

| 角色接口 | 名称 | 宿主节点 |
| --- | --- | --- |
| `PlayerMessage` | `logic` | Logic |
| `SceneMessage` | `logic` | Logic |
| `GMMessage` | `logic` | Logic |
| `BattleMessage` | `battle` | Battle |
| `S2C` | `gateway` | Gateway |
| `GatewayControlMessage` | `gateway` | Gateway |

`AkkaKit.entityTypeKey(actorCmdClass)` 用**角色接口类的简单类名**作为 `EntityTypeKey`（例如 `PlayerMessage`），因此一个角色下的所有 DTO 共享同一个分片类型；实体 ID 由调用方给出（`playerId`、`sceneId`、`battleId`、`channelId`）。

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

身份字段的来源：

| 字段 | 上行来源 | 下行用途 |
| --- | --- | --- |
| `playerId` | DTO 中的 `playerId`（或 `player_id`），由 `RouteValueExtractors.playerId()` 反射读取 | 决定 Logic 中的实体 ID |
| `channelId` | JWT 的 `cid` claim，认证后由 `RequestContext.withIdentity` 覆盖 | 决定 Gateway 中 `ChannelActor` 的实体 ID |

`RouteValueExtractors.channelId()` 会先找 `channelId()`，找不到再退到 `sessionId()`。但网关在认证后会用认证上下文里的 `channelId` 覆盖 DTO 里的值（`GatewayMessageRouter.authenticatedChannelId`），这是防止客户端伪造下行目标的刻意设计，不要改成优先取 DTO 值。

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

- Login `/api/auth/issue`：请求头 `X-Channel-Id` 缺省时生成 UUID，作为 JWT 的 `cid`；
- Gateway 认证成功后把 `channelId -> ClientEndpoint` 写入 `ChannelRegistry`；
- `ClientEndpoint` 抽象统一 TCP Channel、UDP 会话、KCP 会话与 WebSocket 通道，`write(NetMessage)` 负责各自的编码与发送；
- `S2C` 消息经 `ChannelActor` 处理：找到 endpoint、检查 `isActive()`、写入 `NetMessage`，失败计入 `gateway.s2c.dropped`；
- `max-connections-per-account`（默认 1）在认证时拒绝同一 `channelId` 的重复活跃连接。

`ChannelRegistry` 同时注册 `gateway.online.connections` 指标，并提供登录往返计时用的 `recordLoginIngress` / `takeLoginIngress`。

## 玩家状态模型

`PlayerState` 对应 Redis hash `player:{playerId}`，字段集合固定：`id`、`name`、`email`、`online`、`last_login_at`、`updated_at`、`version`；`markDirty` 只接受这些已知字段，其他值抛 `IllegalArgumentException`。

生命周期阶段（`PlayerLifecycle.Phase`）：

```text
NEW -> LOADING -> ONLINE -> DESTROYING -> DESTROYED
         ^                               |
         +---------- failLoading --------+
```

- `beginLoading` 只在 `NEW` 生效；加载超时由 `LOAD_TIMEOUT_KEY` 定时器控制，`LOAD_TIMEOUT_MS = 3000`；
- 加载期间的消息进入 512 容量的 `StashBuffer`，加载完成后 `unstashAll`；
- `markOnline` 把 `LOADING` 推进到 `ONLINE`，并设置 `online=true`、`last_login_at`/`updated_at`、`version+1` 与对应脏字段；
- 销毁时 `beginDestroyLifecycle` 要求当前为 `ONLINE`，随后写离线快照并提交数据库同步；
- `shouldPersistOnStop()` 在 Actor 意外停止时判断是否补偿持久化（`ONLINE` 或 `DESTROYING`）。

`version` 是乐观锁：每次状态变更自增，与 `PlayerUpsertSql` 的 `WHERE version = ?` 配合，`batchUpdate` 返回 0 表示版本冲突，记录保留到下一轮重试。`clearDirtyMatching` 只清除"值仍等于已持久化内容"的字段，避免异步写期间的新修改被误清。

## 投递语义与有界性

这套系统刻意选择"有界 + 丢弃 + 可观测"，而不是无限缓冲或可靠投递：

| 位置 | 上限 | 超限行为 |
| --- | --- | --- |
| 网关全局准入 | 4096 在途 | 拒绝该消息，`gateway.ingress.rejected{reason=capacity}` |
| 网关单连接准入 | 256 在途 | 同上 |
| 网关过期请求 | `deadlineEpochMs` | 拒绝，`reason=expired` |
| 玩家加载暂存 | 512 条 | 由 `StashBuffer` 语义决定（溢出时由 Pekko 处理） |
| DBSync 脏缓冲 | `maxDirtyRecords` / `maxDirtyBytes` | `submit` 返回 false，状态不落库 |
| DBSync 重试 | `maxRetryAttempts` | 进入 `terminalFailures`，不再重试并记 `dbsync.retry.exhausted` |
| 日志消费 | 死信 key `log:events:dead-letter` | 坏消息跳过整批，写入死信 |
| Actor 信封解码失败 | — | 丢弃并记 `xs.rpc.actor.envelope.dropped{reason}` |

没有任何一层提供端到端的投递确认或重放：`entityRef.tell` 是 at-most-once。需要可靠性的场景必须由业务自己在状态层做补偿（例如玩家状态以最终一次快照 + 乐观锁收敛）。
