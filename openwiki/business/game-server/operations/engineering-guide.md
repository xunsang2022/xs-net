---
type: Operations Guide
title: 工程与运维指南
description: 构建与测试、本地运行、配置优先级、容量与调优参数、故障排查、按变更区域的验证矩阵，以及仓库协作规范与近期演进。
resource: game-server/README.md
tags: [xs-net, operations, build, troubleshooting]
---
# 工程与运维指南

## 构建环境

本机没有全局 Maven，统一使用仓库根目录的 `mvnw.cmd`：

```powershell
.\mvnw.cmd -DskipTests package
```

包装脚本固定 `JAVA_HOME`（JDK 21）并显式传入 `-Dmaven.repo.local="$env:USERPROFILE\.m2\repository"`，避免 Maven 解析到 `C:\.m2\repository`。必须直接调用 Maven 时，使用 `F:\Program Files\JetBrains\IntelliJ IDEA 2025.1.1\plugins\maven\lib\maven3\bin\mvn.cmd`（3.9.9）并显式指定本地仓库；不要依赖 PATH 中的 `mvn`。

## 构建与测试

| 目的 | 命令 |
| --- | --- |
| 全量构建 | `.\mvnw.cmd clean install` |
| 只构建框架 | `.\mvnw.cmd -pl xs-net -am clean install` |
| 只构建服务端 | `.\mvnw.cmd -pl game-server -am clean install` |
| 全量测试 | `.\mvnw.cmd test` |
| 框架测试 | `.\mvnw.cmd -pl xs-net -am test` |
| 服务端测试 | `.\mvnw.cmd -pl game-server -am test` |
| 单模块 + 上游 | `.\mvnw.cmd -pl game-server/game-node-modules -am test` |
| 单测试类 | `.\mvnw.cmd -pl xs-net/xs-net-message -am test -Dtest=NetMessageGoldenTest` |
| 跳过测试打包 | `.\mvnw.cmd -DskipTests package` |

测试框架不统一，新增测试时跟随所在模块的既有依赖：

| 风格 | 模块 |
| --- | --- |
| JUnit 3（`junit.framework.TestCase`，`junit:3.8.1`） | `xs-net-message`、`xs-net-tools` |
| JUnit 4（`org.junit.Test`） | `xs-net-kernel`、`game-message-action` |
| JUnit 5（`org.junit.jupiter.api`） | `xs-net-transport`、`xs-net-rpc-maven-plugin`、`game-node-gateway`、`game-node-login`、`game-node-modules`、`game-node-battle` |

`game-message-action` 的黄金样本测试（`GameMessagesGoldenTest` + `src/test/resources/golden/*.hex`）对线格式做字节级断言；任何信封字段或编码顺序的改动都必须解释这些样本为什么需要更新。

## 本地运行

### 基础设施

```powershell
docker compose up -d
docker compose ps
```

`docker-compose.yml` 启动 PostgreSQL 17（5432，库 `gamedb`，`deploy/pg-init.sql` 自动初始化）与 Redis 7（6379，`deploy/redis.conf`）。

### 启动顺序

1. `game-node-login`（HTTP 8081）
2. `game-node-modules`（Logic，Pekko 4001，指标 9090）或 `game-node-battle`（Battle，Pekko 4501）
3. `game-node-gateway`（HTTP 8080、TCP 8082、WebSocket 8083、UDP 2002、KCP 2003、Pekko 2001）

集群名固定为 `game-system`，本地种子节点为 `gateway@127.0.0.1:2001` 与 `logic@127.0.0.1:4001`。同机运行多个 Gateway 或 Logic 时，必须为每个实例分配不同的 `PEKKO_PORT`。

### 端口汇总

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| Gateway | 8080 / 8082 / 8083 / 2002 / 2003 / 2001 | Actuator+GM / TCP / WebSocket / UDP / KCP / Pekko Artery |
| Login | 8081 | HTTP |
| Logic | 4001 / 9090 | Pekko Artery / 指标与 GM 追踪 |
| Battle | 4501 | Pekko Artery |
| 基础设施 | 5432 / 6379 | PostgreSQL / Redis |

### Logic 启动脚本

```powershell
$env:LOGIC_GM_TOKEN = 'replace-with-a-long-random-token'
.\deploy\local\start-logic.ps1 -Build
```

脚本会打包 `game-node-modules`（`-Build` 或产物缺失时），并以 `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Duser.home=<repo>\.log -Darthas.home=<target>\arthas -Darthas.ip=127.0.0.1 -Darthas.httpPort=-1` 启动，标准输出写入 `.log/logic-<时间戳>.log`。

### IDEA 中运行 Logic 的 JVM 参数

在 IDEA 中直接运行 `GameLogicApplication` 时，必须在 Run Configuration 的 **VM options** 中添加以下参数，否则 Pekko Artery（Aeron UDP 传输）会因 JDK 21 模块系统限制而启动失败：

```
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
```

**错误现象**（缺少参数时）：

```
Caused by: java.lang.reflect.InaccessibleObjectException: Unable to make field private final java.util.Set sun.nio.ch.SelectorImpl.selectedKeys accessible: module java.base does not "opens sun.nio.ch" to unnamed module
```

**原因**：Aeron 的 `TransportPoller` 需要反射访问 `sun.nio.ch.SelectorImpl` 的私有字段，JDK 21 默认禁止这种访问。

### Logic 配置WSL Ubuntu 24.04 / JRE 21 的一次本机测量中，从相同的 JVM 启动标记到 Shard Region 初始化：原启动约 16.0 秒，迁移后约 20.1 秒，增加约 4.1 秒。此测量未等待配置中的 Pekko seed node 加入（`127.0.0.1:2001` 未运行），不可视为集群就绪时间；Spring Boot 报告的应用上下文启动耗时不等于进程总启动耗时。

### Logic 配置

`game-node-modules/src/main/resources/application.yml` 由 Spring Boot 加载。Redis 使用 `spring.data.redis.*`，PostgreSQL 连接池使用 `spring.datasource.*`；Logic 自有配置绑定到 `game.*`，并支持标准外部配置覆盖（命令行、环境变量及 profile 配置）。Pekko 集群地址仍由 `application.conf` 管理。DBSync、MyBatis Mapper 和日志消费由 `LogicNodeConfiguration` 创建，`LogicNodeRuntime` 负责运行时启动与关闭。

### 压测客户端

原 `game-client` 模块已移除。登录压测使用统一生成的 Unity 控制台工程：

```powershell
.\scripts\Generate-Sdks.ps1 -Only Unity
dotnet run --project xs-net/xs-net-sdk/target/code/unity/LoadTest/XsNet.LoadTest.csproj -- --host 127.0.0.1 --protocol tcp --port 8082 --count 1000 --concurrency 200 --secret "<JWT_SECRET>"
```

## 配置

### Gateway / Login（Spring Boot）

`game-node-gateway/src/main/resources/application.yml` 与 `game-node-login/src/main/resources/application.yml` 是 Spring 配置，支持 Spring Boot 的标准外部化配置（命令行、环境变量、`application-*.yml`）。关键项：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 / 8081 | HTTP |
| `game.netty.port` | 8082 | TCP |
| `game.netty.boss-threads` / `worker-threads` / `business-threads` | 1 / 64 / 64 | 0 表示 2×CPU |
| `game.netty.max-frame-length` | 65536 | 单帧上限，需与 SDK 的 `MAX_FRAME_LENGTH` 一致 |
| `game.netty.auth-timeout-seconds` | 10 | 握手超时 |
| `game.netty.max-connections-per-account` | 1 | 同 channelId 并发连接上限 |
| `game.netty.reader-idle-timeout-seconds` | 300 | 读空闲超时，0 关闭 |
| `game.netty.write-buffer-*-water-mark` | 32768 / 65536 | 出站缓冲水位 |
| `game.websocket.port` / `path` | 8083 / `/game` | WebSocket |
| `game.udp.*` / `game.kcp.*` | 见 yml | 端口、窗口、重传间隔、超时、线程数 |
| `game.management.token` | `${GM_TOKEN:}` | 为空时 `/admin/gm/*` 返回 404 |
| `jwt.secret` / `jwt.expiration` | `${JWT_SECRET:}` / 86400000 | 必须由环境变量 `JWT_SECRET` 注入（Gateway 与 Login 一致，≥32 字节）；空或过短密钥会由 `JwtUtil` 启动快速失败，配置文件不再保留明文默认值 |

### Logic / Battle

Logic 配置由 Spring Boot 外部化配置加载。Redis、数据源分别使用 `spring.data.redis.*` 和 `spring.datasource.*`；线程池、DBSync、管理端点及热配置使用 `game.*` 属性绑定。命令行、环境变量和 profile 配置均可覆盖 YAML。Battle 仍为普通 Java，主要使用 `application.conf` 与 Pekko 环境变量。

`game.pekko.*` 不用于覆盖集群网络地址；Logic 与 Battle 的 hostname / port / seed nodes 仍由各自 `application.conf` 和其中声明的 `PEKKO_HOSTNAME` / `PEKKO_PORT` 环境变量控制。集群部署传入的环境变量需使用 Spring Boot 对应属性名（例如 `SPRING_DATA_REDIS_HOST`、`SPRING_DATASOURCE_URL`），不能假设旧的 `REDIS_HOST` / `DB_URL` 会自动映射。

### Pekko

`xs-net-message/src/main/resources/application.conf` 提供公共部分（cluster provider、关闭 Java 序列化、注册 `NetMessageSerializer`）。各节点自己的 `application.conf` 追加 `roles`、`seed-nodes`、Artery 端口与分片参数。HOCON 文件中**禁止**写 `${ENV_VAR:default}`——冒号在 HOCON 中是 path token，会报 `BadPath`；必须写成"默认值 + 下一行 `${?ENV_VAR}` 覆盖"。

## 容量与调优参数

| 参数 | 位置 | 默认 | 影响 |
| --- | --- | --- | --- |
| `numberOfShards` | 各 `application.conf` | 100 | 分片数，建议 10 × 预期节点数 |
| `xs-entity-dispatcher.fixed-pool-size` | `application.conf` | 256（`XS_ENTITY_POOL`） | 未指定 `@ActorDispatcher` 的实体并发度 |
| `player-dispatcher` | Logic `application.conf` | 8（`XS_PLAYER_POOL`） | 玩家 Actor 并发度 |
| `game.udp.window` / `retry-interval-ms` | Gateway yml | 32 / 200 | 轻量可靠 UDP 的在途窗口与重传节奏 |
| `game.kcp.window` / `retry-interval-ms` | Gateway yml | 32 / 200 | KCP 窗口与节拍 |
| 网关准入 | 代码常量 | 全局 4096、每连接 256 | `GatewayMessageRouter` 内的硬编码上限 |
| `game.db.batch-size` | Logic yml | 100 | 单次 `batchUpdate` 记录数 |
| `game.db.bgsave-seconds` | Logic yml | 3 | DBSync flush 周期 |
| `game.db.flush-concurrency` | Logic yml | 8 | flush 分片并行度 |
| `game.io.redis-threads` / `db-threads` | Logic yml | 16 / 8 | 阻塞 I/O 池大小（队列 1024，满则拒绝） |
| `game.storage.sync-interval-ms` | Logic yml | 1000 | 玩家脏数据刷新周期，可运行时热更 |

运行时热更：`-Dgame.config.path=<yaml>` 启用 `LogicRuntimeConfiguration`，按 `game.management.config-reload-interval-ms` 轮询文件修改时间，目前只应用 `game.storage.sync-interval-ms` 与 `game.logging.level`。

## 故障排查

| 症状 | 检查点 |
| --- | --- |
| 节点起不来或反复 HandshakeTimeout | 种子节点是否可达；`PEKKO_HOSTNAME` / `PEKKO_PORT` 是否唯一；集群名是否都是 `game-system`；种子列表里不要有未部署的地址 |
| 客户端认证一直被拒 | 两端是否注入一致的 `JWT_SECRET`（落到 `jwt.secret`，≥32 字节）；token 是否含 `cid`；是否超过 `auth-timeout-seconds` |
| 连接建立后没有 S2C | `ChannelRegistry` 是否有活跃 endpoint；`gateway.s2c.dropped` 是否增长；客户端是否与服务端 `max-frame-length` 一致 |
| 上行消息静默丢失 | 依次看 `gateway.ingress.rejected{reason=capacity\|expired}`、`xs.rpc.routing.rejected{reason}`、`xs.rpc.actor.envelope.dropped{reason}`；未知 `rpcNum` 只打印 WARN 不打指标 |
| 登录卡住或 Actor 反复停止 | Redis/PostgreSQL 是否可达；`PlayerInfo.loadingModel` 先读 Redis 缓存、未命中再读 DB，读失败会抛出并停止 Actor |
| 状态不落库 | `dbsync.dirty.size` / `dbsync.dirty.bytes` 是否增长；`dbsync.flush.failures` 与 `dbsync.retry.exhausted`；`users` 表结构与 `PlayerMapper.xml` 是否一致；乐观锁冲突是否持续 |
| 日志丢失或重复 | `log:events:dead-letter` 是否有坏消息；消费者组 `db-sync` 的 pending 列表；`source_stream_id` 唯一索引是否存在 |
| 实体分布不均或投递失败 | 角色是否在目标节点注册；`@Roles` 与 `@ActorDispatcher` 是否齐全；是否存在"只声明了 `@Rpc` 但宿主 Actor 里没有单参数处理方法"的消息（生成期不再校验，需人工确认；`GeneratedRpcHandlers` 可作为方法名核对入口） |
| 文档与实现对不上 | 先看 [传输协议说明](../game-node-gateway/transport.md)，实现路径以 `com.game.gateway.netty` 与 `com.game.gateway.kcp` 为准 |

## 按变更区域的验证矩阵

| 变更区域 | 必跑 |
| --- | --- |
| `xs-net-message`（信封、上下文、注册表） | `xs-net-message` 全部测试 + `game-message-action` 的 `GameMessagesGoldenTest` + `RpcTransportTest` |
| `xs-net-kernel`（分片、投递、拦截器、模型契约） | `xs-net-kernel` 全部测试 + `game-node-battle` 的 `BattleActorDispatcherTest` |
| `xs-net-rpc-maven-plugin` / 协议声明 | 插件测试 + 重建 `game-message-action` 并检查 `rpc_protocol_validation.txt`（重点 diff 其 `proto_message_fields:` 段，发现字段号复用/删除）+ `scripts/Generate-Sdks.ps1` |
| `@ProtoRecord` 模型协议（`PlayerInfoProto` → `PlayerInfoData`，`PlayerPO` → 转换器） | 插件测试 + 重建 `game-message-action`（由 `PlayerInfoProto` 生成记录与 `ActorModels.proto`）+ 重建 `game-node-modules`（由 `PlayerPO` 生成 `GeneratedProtoConverters`，并生成 `GeneratedRpcHandlers`）+ `GameMessagesGoldenTest` / `RpcTransportTest` |
| Gateway 传输或认证 | `game-node-gateway` 全部测试；实机验证对应协议的认证与 S2C 往返 |
| Logic 状态与持久化 | `game-node-modules` 全部测试；本地起 Redis+PostgreSQL 跑一次登录/登出，确认 `users` 行与 `version` 变化 |
| Battle 执行模型 | `game-node-battle` 全部测试（含 `BattleLoadTest`） |
| SDK 生成 | `scripts/Generate-Sdks.ps1`；TypeScript 侧 `npm run typecheck && npm run build && npm test`，Unity/C++ 侧跑生成目录中的测试工程 |
| 部署与集群配置 | `deploy/cluster/README.md` 的拓扑验证序列（含停掉 `logic-2` 观察分片恢复与 `dbsync_*` 指标） |
