---
type: Integration Guide
title: 集成与扩展
description: 四种客户端传输、Spring 集成、Redis 与 PostgreSQL、可观测性、GM/Arthas 诊断、多语言 SDK 生成，以及受支持的扩展边界。
resource: game-server/game-node-gateway/src/main/resources/application.yml
tags: [xs-net, integrations, sdk, observability]
---
# 集成与扩展

## 客户端传输

四种入口解决不同层次的问题，但业务目标一致：把认证后的 C2S 消息交给 Logic，把 S2C 消息写回正确的客户端。

| 协议 | 端口 | 分帧边界 | 可靠性来源 | 适配代码 |
| --- | --- | --- | --- | --- |
| TCP | 8082 | XS packet（TCP 流按 `length` 拆帧） | TCP 栈 | `netty/NettyServer`、`NettyChannelInitializer`、`AuthHandler`、`NettyFramer` |
| WebSocket | 8083（`/game`） | 一个 binary frame 一个 XS packet | TCP 栈 | `netty/WebSocketServer`、`WebSocketChannelInitializer`、`WebSocketGameHandler` |
| UDP | 2002 | XS packet 一个 datagram | 项目自研 `UdpReliableLayer` | `netty/UdpServer`、`UdpAuthHandler`、`UdpSession`、`UdpReliableLayer` |
| KCP | 2003 | KCP 段重组后交付 | KCP 状态机 | `kcp/KcpServer`、`KcpAuthHandler`、`KcpSession`、`Kcp` |

共同点：认证后都产出 `channelId`，都通过 `ClientEndpoint` 接口向 `ChannelActor` 暴露 `write(NetMessage)`，都复用同一个 `GatewayMessageRouter`。TCP、WebSocket 和自研 UDP 共用以下包头：

```text
length(4) | packetId(2) | sendSeq(4) | random(4) | headCRC(2) | bodyCRC(2) | body(N)
```

其中 `length` 是 body 长度，CRC 为 CRC-16/CCITT-FALSE；包类型为 `DATA=1`、`ACK=2`、`HELLO=3`、`HEARTBEAT=4`、`ERROR=5`。

使用自动重连时，客户端必须在同一逻辑会话中保持 `random` 不变。网关据此允许同一 `channelId + random` 的新端点替换旧端点，并使用条件删除避免旧连接关闭时删除新端点。该机制只保证连接重新建立，不包含玩家业务状态快照和断线事件回放。

新增一种传输时的最小改动面：

1. 在 `game-node-gateway` 实现一个 `Server`（生命周期）与一个认证 Handler；
2. 用 `ChannelRegistry.register(channelId, endpoint)` 暴露写回端点，`endpoint` 负责编码分帧；
3. 复用 `JwtUtil` 校验首帧，并遵守 `auth-timeout-seconds` / `max-connections-per-account` 语义；
4. 加上该协议专属的 Micrometer 计数器；
5. 不要往 `xs-net-transport` 里放 Netty 相关实现——那里只放与连接无关的契约。

## Spring 集成

`xs-net-core` 提供 `GameFrameworkAutoConfiguration`：

- `@EnableConfigurationProperties(GameFrameworkProperties)`，绑定 `game.framework.*`；
- 创建 `ActorSystem<Void>("game-system")`，在守护者下 spawn `NodeActor`；
- 通过 `@ConditionalOnClass` 暴露 `ClusterSharding` bean。

`ActorRegisterManager` 监听 `ContextRefreshedEvent`，在容器刷新完成后触发 `NodeActor.create(properties)`。`GameFrameworkProperties` 的关键配置：`actor-auto-register-enabled`、`message-auto-register-enabled`、`actor-base-packages`、`message-base-packages`。

`SpringUtil`、`ClassScanner`、`ClassUtil` 用于在 Actor 初始化阶段从容器取 `RpcMessageRegistry` 与扫描消息接口。Gateway 与 Logic 使用 Spring Boot；Logic 的 `GameLogicApplication` 以非 Web 模式运行，`LogicNodeRuntime` 在配置与业务 Bean 就绪后取得框架创建的 `ActorSystem` 并等待终止。Battle 仍是普通 Java 启动，通过 `properties.setActorBasePackages(...)` + `NodeActor.create(...)` 注册节点。

Login 是唯一刻意排除该自动配置的 Spring 应用：它不需要集群与 Actor 系统。

## Redis

Logic 通过 Spring Boot 的 `spring.data.redis.*` 配置 Redis/Lettuce 连接池；`LogicNodeConfiguration` 提供玩家状态用的 `RedisTemplate<String, Object>`，以字符串序列化 key、以 Jackson JSON 序列化 hash value。`RedisStore` 通过 `LogicContext` 静态装配，键空间如下：

| 键 | 类型 | 用途 |
| --- | --- | --- |
| `player:{playerId}` | Hash | 玩家状态（字段见 [领域概念](../../framework/xs-net-message/concepts.md#玩家状态模型)） |
| `idx:player` | Set | 全量玩家索引 |
| `player:online` | Set | 在线玩家集合 |
| `log:events` | Stream | 业务日志事件 |
| `log:events:dead-letter` | Stream | 日志消费坏消息 |

所有阻塞 Redis 调用都在 `redis-io-executor` 上执行，通过 `CompletableFuture` + `pipeToSelf` 回到 Actor。**不要在 Actor 消息处理方法里直接访问 Redis**，否则会阻塞分片调度线程。

## PostgreSQL

Logic 使用 Spring Boot 的 `spring.datasource.*` 创建 Hikari 数据源；连接池参数位于 `spring.datasource.hikari.*`。MyBatis-Plus 的 `SqlSessionFactory`、Mapper 与 `DBSyncEngine` 由 `LogicNodeConfiguration` 提供，玩家写入路径使用 `PlayerMapper` 和 XML Mapper。

表结构由 `deploy/pg-init.sql` 初始化（Docker Compose 首次创建空库时执行）：

- `users(id, name, email, online, last_login_at, updated_at, version)`；
- `log_events(id, source_stream_id, player_id, type, level, message, created_at)`，其中 `source_stream_id` 有唯一索引用于幂等消费；
- `idx_log_events_player_created(player_id, created_at DESC)`。

玩家运行时模型 `PlayerPO` 同时映射 `users` 表并承载 Actor 状态；它继承 `GameModel`（MyBatis-Plus `Model`），实现 xs-net 的持久化无关 `XsModel` 契约。通用字段访问、脏字段跟踪和 Hash 转换由 `XsModelSupport` 提供，`XsModelHashMapper` 负责 Redis Hash 与模型互转。玩家加载/创建和批量写入分别由 `PlayerMapper.loadOrCreate`、`PlayerMapper.upsertBatch` 执行。改动字段时必须同步检查 `PlayerPO`、`PlayerMapper.xml` 与 `deploy/pg-init.sql`。

## 可观测性

### 指标

| 位置 | 指标 |
| --- | --- |
| Gateway 认证/连接 | `gateway.auth.success`、`gateway.auth.failure`、`gateway.connection.rejected`、`gateway.online.connections` |
| Gateway 入站 | `gateway.c2s.messages`、`gateway.ingress.rejected{reason}` |
| Gateway 下行 | `gateway.s2c.messages`、`gateway.s2c.dropped`、`gateway.s2c.arrive.nano`、`gateway.s2c.write.nano`、`gateway.login.roundtrip` |
| 各传输自有计数器 | `gateway.udp.*`、`gateway.websocket.*`、KCP 对应计数器 |
| 框架投递 | `xs.rpc.routing.rejected{reason}`、`xs.rpc.actor.envelope.dropped{reason}`、`xs.rpc.context.calls{source_node}` |
| Logic 登录 | `login.redis.load.duration{cache}`、`login.database.load.duration`、`login.redis.create.duration`、`login.logic.total.duration`、`login.s2c.send.nano` |
| Logic 持久化 | `dbsync.flush.duration`、`dbsync.flush.failures`、`dbsync.retry.exhausted`、`dbsync.dirty.size`、`dbsync.dirty.bytes` |

### 端点与部署

- Gateway：Spring Boot Actuator，8080，暴露 `health,info,metrics,prometheus`，并启用 liveness/readiness 探针；
- Login：Actuator，8081；
- Logic：`LogicPrometheusServer`，9090，路径 `/metrics`，同时承载受令牌保护的 `/admin/gm/trace`；令牌为空时管理端点不注册；
- `deploy/observability/`：Prometheus 抓取配置 + 三条告警规则 + Grafana provisioning 与 `xs-net-runtime` 仪表盘；
- `deploy/cluster/docker-compose.yml`：1 Login + 2 Gateway + 2 Logic + Redis + PostgreSQL，配合 `application-cluster.conf` 使用 `gateway-1` / `logic-1` 作为种子。

## GM 与 Arthas 诊断

**GM 踢人**：Gateway 的 `GmController`（`/admin/gm/kick`）要求 `X-GM-Token`，令牌未配置时返回 404；`GMActor` 先写审计日志，写失败即拒绝（fail-closed）。

**协议追踪**：Logic 启动时解包并预加载 Arthas agent（`maven-dependency-plugin` 在 `package` 阶段解压 `arthas-packaging:4.0.5:bin`），并以 `-Darthas.ip=127.0.0.1 -Darthas.httpPort=-1` 限制为仅本机、无 HTTP 端口。`/admin/gm/trace` 接收 `protocolNumber` 与 `timeoutMs`（1000–30000），由 `ProtocolTraceTargetResolver` 把协议号解析为 Actor 与处理方法，再由 `ProcessArthasTraceClient` 执行追踪。客户端登录协议 1000 在网关被转换为 3003，因此追踪登录时要填 3003。

## 多语言 SDK 生成

SDK 不维护为仓库根目录下的独立工程，而是由 `xs-net/xs-net-sdk` 统一生成：

| 语言 | 手写模板 | 产物 |
| --- | --- | --- |
| Unity | `xs-net/xs-net-sdk/src/main/sdk/unity/` | `target/code/unity/`（含 `Runtime/Networking` 手写运行时与 `LoadTest` 控制台工程） |
| TypeScript | `.../sdk/typescript/` | `target/code/typescript/`（`ts-proto` 生成 + `npm run typecheck/build/test`） |
| Cocos Creator | `.../sdk/cocos/` | `target/code/cocos/`（复用 TS 协议代码 + `CocosWebSocketTransport`，不含 Node TCP/UDP 依赖） |
| C++ | `.../sdk/cpp/` | `target/code/cpp/`（CMake，含 `frame_test` / `kcp_test`） |

唯一入口是 `.\scripts\Generate-Sdks.ps1`，支持 `-Only Unity|TypeScript|Cocos|Cpp`、`-OutputRoot`、`-Protoc`、`-TsProtoPlugin`、`-CocosProjectRoot`。工具链依赖：本地 Maven 缓存中的 `protoc 3.25.5`，以及 TypeScript 模板目录下的 `npm install`（提供 `protoc-gen-ts_proto`）。

边界约定：

- 生成的 proto、protobuf 代码与构建目录都在输出目录内，**不要手工修改 `target/code` 下的任何文件**，也不要提交该目录；
- Unity 的 `Runtime/Networking/NetMessage.cs` 是手写运行时，因此 Unity 目标不输出 `net_message.proto` 的 C# 类型；TypeScript 与 C++ 会生成框架 proto 类型；
- 协议变更后必须重新生成并跑对应语言的测试，详见 [工程运维](operations/engineering-guide.md)。

## 扩展点小结

| 需求 | 受支持的接入点 |
| --- | --- |
| 新增业务协议 | `@Rpc`/`@ProtoField` record + Actor 单参数处理方法 |
| 新增角色/节点类型 | `@Roles` 接口 + `RpcActor<Role>` + 新节点的 `application.conf` 角色声明 |
| 新增传输 | `game-node-gateway` 内新增 Server + 认证 Handler + `ClientEndpoint` 实现 |
| 绕过分片邮箱的快速路径 | `AkkaKit.registerDeliveryInterceptor(roleClass, interceptor)` |
| 指定实体调度器 | `@ActorDispatcher("pekko.actor.xxx")` + `application.conf` 中的 dispatcher 定义 |
| 新增客户端语言 | `xs-net-rpc-maven-plugin` 的导出 Mojo + `src/main/sdk/<lang>` 模板 |
| 运行时热更配置 | `-Dgame.config.path=<yaml>` + `LogicRuntimeConfiguration` 轮询 |

不受支持或尚未实现：独立的注册中心/发现服务、SBE 序列化、可靠投递与重放、`DURABLE_EVENT` 投递语义、按传输过滤路由（`RpcRouteDescriptor.transports` 恒为全集）、`publicVisible` 的强制拦截。
