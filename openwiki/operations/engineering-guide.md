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
| JUnit 4（`org.junit.Test`） | `xs-net-core`、`game-message-action` |
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
| `jwt.secret` / `jwt.expiration` | 开发默认值 / 86400000 | Gateway 与 Login 必须一致，≥32 字节 |

### Logic / Battle

Logic 的 `application.yml` 由 SnakeYAML 手工加载，Battle 只用 `application.conf` + 环境变量。**Logic 的配置来源并不统一**，这是当前最容易被误解的一点：

| 参数组 | 来源 | 是否支持环境变量 |
| --- | --- | --- |
| `game.redis.*` | 仅 `application.yml` | 否 |
| `game.db.*` | 仅 `application.yml` | 否 |
| `game.io.*`、`game.storage.sync-interval-ms`、`game.logging.level` | 仅 `application.yml` | 否 |
| `game.management.metrics.host` / `metrics.port` / `token` / `arthas.home` / `arthas.telnet-port` / `config-reload-interval-ms` | `config(key, envKey, default)` | 是：`LOGIC_METRICS_HOST`、`LOGIC_METRICS_PORT`、`LOGIC_GM_TOKEN`、`LOGIC_ARTHAS_HOME`、`LOGIC_ARTHAS_TELNET_PORT`、`LOGIC_CONFIG_RELOAD_INTERVAL_MS` |
| `game.pekko.*` | 未在 `application.yml` 中生效 | 由 `application.conf` 的 `PEKKO_HOSTNAME` / `PEKKO_PORT` 覆盖（Battle 另有 `BATTLE_PEKKO_HOSTNAME` / `BATTLE_PEKKO_PORT`） |

对支持环境变量的那组，优先级为系统属性 > 环境变量 > YAML > 内置默认值。对 Redis/DB 组，`deploy/cluster/docker-compose.yml` 中给 Logic 传入的 `REDIS_HOST` / `DB_URL` 目前**不会生效**——这是一个需要修正的部署漂移点，参见本文末的待办。

### Pekko

`xs-net-message/src/main/resources/application.conf` 提供公共部分（cluster provider、关闭 Java 序列化、注册 `NetMessageSerializer`）。各节点自己的 `application.conf` 追加 `roles`、`seed-nodes`、Artery 端口与分片参数。HOCON 文件中**禁止**写 `${ENV_VAR:default}`——冒号在 HOCON 中是 path token，会报 `BadPath`；必须写成"默认值 + 下一行 `${?ENV_VAR}` 覆盖"。

## 容量与调优参数

| 参数 | 位置 | 默认 | 影响 |
| --- | --- | --- | --- |
| `numberOfShards` | 各 `application.conf` | 100 | 分片数，建议 10 × 预期节点数 |
| `xs-entity-dispatcher.fixed-pool-size` | `application.conf` | 256（`XS_ENTITY_POOL`） | 未指定 `@ActorDispatcher` 的实体并发度 |
| `player-dispatcher` / `scene-dispatcher` | Logic `application.conf` | 8（`XS_PLAYER_POOL` / `XS_SCENE_POOL`） | 玩家/场景 Actor 并发度 |
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
| 客户端认证一直被拒 | Gateway 与 Login 的 `jwt.secret` 是否完全一致（≥32 字节）；token 是否含 `cid`；是否超过 `auth-timeout-seconds` |
| 连接建立后没有 S2C | `ChannelRegistry` 是否有活跃 endpoint；`gateway.s2c.dropped` 是否增长；客户端是否与服务端 `max-frame-length` 一致 |
| 上行消息静默丢失 | 依次看 `gateway.ingress.rejected{reason=capacity\|expired}`、`xs.rpc.routing.rejected{reason}`、`xs.rpc.actor.envelope.dropped{reason}`；未知 `rpcNum` 只打印 WARN 不打指标 |
| 登录卡住或 Actor 反复停止 | Redis/PostgreSQL 是否可达；`LOAD_TIMEOUT_MS = 3000` 是否触发；缓存读失败会转 DB，DB 也失败才停止 Actor |
| 状态不落库 | `dbsync.dirty.size` / `dbsync.dirty.bytes` 是否增长；`dbsync.flush.failures` 与 `dbsync.retry.exhausted`；`users` 表结构与 `PlayerUpsertSql` 是否一致；乐观锁冲突是否持续 |
| 日志丢失或重复 | `log:events:dead-letter` 是否有坏消息；消费者组 `db-sync` 的 pending 列表；`source_stream_id` 唯一索引是否存在 |
| 实体分布不均或投递失败 | 角色是否在目标节点注册；`@Roles` 与 `@ActorDispatcher` 是否齐全；是否存在"只实现了 `@Rpc` 但没有 Actor 处理方法"的消息（构建期就会失败） |
| 文档与实现对不上 | `docs/udp-tcp-kcp-websocket.md` 引用的 `com.game.gateway.transport.tcp.*` 已不存在；以 `com.game.gateway.netty` 与 `com.game.gateway.kcp` 为准 |

## 按变更区域的验证矩阵

| 变更区域 | 必跑 |
| --- | --- |
| `xs-net-message`（信封、上下文、注册表） | `xs-net-message` 全部测试 + `game-message-action` 的 `GameMessagesGoldenTest` + `RpcTransportTest` |
| `xs-net-core`（分片、投递、拦截器） | `xs-net-core` 全部测试 + `game-node-battle` 的 `BattleActorDispatcherTest` |
| `xs-net-rpc-maven-plugin` / 协议声明 | 插件测试 + 重建 `game-message-action` 并检查 `rpc_protocol_validation.txt` + `scripts/Generate-Sdks.ps1` |
| Gateway 传输或认证 | `game-node-gateway` 全部测试；实机验证对应协议的认证与 S2C 往返 |
| Logic 状态与持久化 | `game-node-modules` 全部测试；本地起 Redis+PostgreSQL 跑一次登录/登出，确认 `users` 行与 `version` 变化 |
| Battle 执行模型 | `game-node-battle` 全部测试（含 `BattleLoadTest`） |
| SDK 生成 | `scripts/Generate-Sdks.ps1`；TypeScript 侧 `npm run typecheck && npm run build && npm test`，Unity/C++ 侧跑生成目录中的测试工程 |
| 部署与集群配置 | `deploy/cluster/README.md` 的拓扑验证序列（含停掉 `logic-2` 观察分片恢复与 `dbsync_*` 指标） |

## 仓库协作规范

- 每个工作单元从当前集成分支（`build-1.0`）切出模块分支，不要直接在 `build-1.0` 或 `master` 上提交；
- 提交信息遵循 Conventional Commits（`feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `build:` / `chore:`，可带作用域），描述必须准确反映变更内容；
- 合并回 `build-1.0` 使用 `--no-ff`，基于最新集成分支，并删除已合并分支；
- 不要提交凭据、本地日志（`.log/`）、生成目录（`target/`）或压测数据；
- 面向客户端的 RPC 编号变更必须有兼容性分析；管理与内部 Actor 消息在缺少显式授权与版本化设计前不得暴露给玩家传输；
- 许可证尚未定案：在维护者决定并完成依赖许可审计前，不要添加许可证头或引入外部源码。

## 近期演进（来自 git 历史）

最近 50 次提交的主题脉络：

- **运行时自研化**：从 Akka 迁移到 Apache Pekko 1.1.5，移除 SBE 依赖，协议统一为 Protobuf；
- **上下文与可靠边界**：`feat(runtime): add request context propagation`、`feat(actor): propagate contextual deliveries`、`fix(core): harden RPC actor message boundaries`、`fix(dbsync): add bounded state and reliable log delivery`；
- **传输统一**：`feat(transport): unify gateway transport and admission`、`feat(gateway): add standard KCP reliable-UDP transport`；
- **战斗独立化**：`feat(battle): add sharded battle actor` → `feat(battle): add serialized battle executor` → `refactor(battle): split battle actor into dedicated service`；
- **SDK 统一生成**：`feat(sdk): unify multi-language SDK generation` → `refactor(sdk): consolidate client SDK templates` → Cocos 目标与自定义输出根目录；
- **协议元数据**：`feat(rpc): generate route metadata and protocol reports`；
- **当前最新**：`feat(actor): 支持组件级 RPC 处理并拆分玩家状态逻辑`——引入 `ActorServices` 与 `RpcHandlerMetadata.componentField`，把 `PlayerActor` 拆成 `PlayerLifecycle` + `PlayerStateSync`，并新增 `resolveCreateMethod` 的 `create(String)` 形态。

这条脉络解释了仓库当前的几个取舍：没有独立注册中心（依赖 Pekko Cluster）、没有可靠投递（改用有界缓冲 + 指标 + 最终一致快照）、业务逻辑与传输实现分离（框架不依赖 Netty）。

## 待补充证据与已知漂移

- `deploy/cluster/docker-compose.yml` 给 Logic 传的 `REDIS_HOST` / `DB_URL` 等环境变量在 `GameLogicApplication` 中不会被读取；同时 Logic `application.yml` 的默认 Redis/DB 地址是局域网地址 `172.21.255.41`，容器内不可达。集群拓扑需要先修正这一点才能按 README 验证。
- `docs/udp-tcp-kcp-websocket.md` 描述的是 `com.game.gateway.transport.{tcp,udp}` 结构，与当前 `netty` / `kcp` 包不一致。
- 仓库没有 CI 工作流（`.github/` 下只有 Issue/PR 模板），所有验证依赖本地执行。
