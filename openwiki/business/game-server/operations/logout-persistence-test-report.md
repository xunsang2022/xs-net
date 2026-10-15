---
type: Test Report
title: 登出落库与 Redis 写入改造测试报告
description: 将玩家状态改为「每条 Actor 消息处理完 flush 一次脏字段到 Redis、登出落库」，并完成 2000~30000 CCU 阶梯压测，给出稳定水位与临界点。
resource: game-server/game-node-modules
tags: [xs-net, logic, persistence, redis, load-test, capacity]
---
# 登出落库与 Redis 写入改造测试报告

> 测试日期：2026-10-14
> 被测版本：工作树 `master`（未提交）

## 1. 目标

1. 玩家状态持久化由「在线每 1s 周期刷脏落库」改为「仅登出时落库」。
2. **数据写入必须先落 Redis（持久化）**，防止落库过程中状态丢失。
3. 写入时机由「每次 `setProperty` 逐字段写」改为 **「一条 Actor 消息处理完一次性 flush 脏字段」**，避免逐字段同步往返拖垮登录吞吐。
4. 改造后重新压测，给出可稳定承载的 CCU 水位。

## 2. 变更内容

### 2.1 持久化路径（最终设计）

| 文件 | 变更 |
| --- | --- |
| [PlayerInfo.java](../../../../game-server/game-node-modules/src/main/java/com/game/logic/actor/player/core/PlayerInfo.java) | `registerHandlers` 三个处理器统一包一层 `persistAfter(...)`：一条消息处理完成后，若玩家在线且有脏字段，调用 `RedisStore.saveDirty` 一次性刷入 Redis。登出/GM 踢人时 `beginDestroy` 直接落库 |
| [RedisStore.java](../../../../game-server/game-node-modules/src/main/java/com/game/logic/redis/RedisStore.java) | `saveDirty(model, entityId)`：取模型当前全部脏字段，一次 `HSET` 写入 Redis 哈希（`@RedisKey` 前缀 + entityId），再清脏；无脏字段直接返回 |
| [XsModelSupport.java](../../../../xs-net/xs-net-kernel/src/main/java/com/xs/kernel/model/XsModelSupport.java) | 移除 `afterPropertySet` 逐字段钩子与 4 参构造，恢复单构造 `(model, fieldMapper, beforePropertySet)`；保留 `getDirtyFields/hasDirtyFields/clearDirtyMatching/toHash/toValueMap` |
| [GameModel.java](../../../../game-server/game-event-sourcing/src/main/java/com/game/eventsourcing/persistence/GameModel.java) | 恢复单构造透传 `beforePropertySet`，删除 `afterPropertySet` 默认实现 |
| [PlayerPO.java](../../../../game-server/game-node-modules/src/main/java/com/game/logic/actor/player/db/PlayerPO.java) | 删除 `afterPropertySet` 覆写；保留 `beforePropertySet`（捕获 expectedVersion）与 `getVersionValue()` |
| [ActorServerSync.java](../../../../game-server/game-node-modules/src/main/java/com/game/logic/actor/core/ActorServerSync.java) | `persistAsync(onPersisted)`：异步落库，**全部确认后才回调**；db-io 池饱和或写入未确认时降级到有界缓冲批写重试，返回 false 且不回调 |

**关键语义**：在线期间只在「消息边界」写 Redis、不落库；登出时把最终状态直接落库，**DB 全部确认写入后才删 Redis 缓存**，未确认则保留 Redis 副本兜底（数据先落 Redis，天然满足防丢失）。

> 说明：曾尝试"登出同步落库"，但同步阻塞 player-dispatcher（登录/登出共用 8 线程），
> 2 万 CCU 时 `logout_failed` 由 2,385 恶化到 15,199，故保留异步 + 回调方案。

### 2.2 日志事件路径（原本已满足）

业务日志走 `RedisStore.writeLog` → `XADD log:events`（Redis Stream）→ `LogConsumer` → PostgreSQL。
写入先落 Redis Stream，天然满足"写入先落 Redis、防止消息丢失"。

### 2.3 随之失效的代码/配置清理

- 删除 `FlushDirty` 消息类、`LogicContext.syncIntervalMs`、`LogicNodeProperties.Storage`、
  `game.storage.sync-interval-ms`（含运行时热更分支）、`SimpleActorServer.FLUSH_KEY`
- 同步调整 `LogicContextTest`、`LogicRuntimeConfigurationTest` 与 openwiki 文档

### 2.4 压测端

[Program.cs](../../../../xs-net/xs-net-sdk/src/main/sdk/unity/LoadTest/Program.cs)：新增 `--hold-seconds`（保持连接测真 CCU）与 `--logout`（登出验证）；`ReadTimeoutMs = -1`、`AutoReconnect = false`；输出 `dropped_during_hold` / `sustained_ccu` / `logout_failed`。

### 2.5 附带修复（框架 bug）

[VoConverters.java](../../../../xs-net/xs-net-kernel/src/main/java/com/xs/kernel/model/VoConverters.java)：`bootstrapped` 标志在 `Class.forName` 之前置位，并发首次转换误报 `No protocol converter registered`；修复后 10 并发登录由 4/10 成功变为 10/10。

## 3. 测试环境

| 项 | 值 |
| --- | --- |
| 宿主 | Windows 48GB / 16 逻辑核（8G 规格用 JVM 堆上限模拟） |
| JDK | IntelliJ JBR 21.0.6 |
| Gateway / Logic / Login 堆上限 | 3g / 4g / 1g |
| Redis | 容器 `xs-net-redis-test`，宿主 16379，密码 123456 |
| PostgreSQL | 容器 `xs-net-pg-test`，宿主 15432，库 `gamedb` |
| 压测端 | `XsNet.LoadTest`（net8.0，roll-forward 到 .NET 10） |

压测前**清空测试 Redis 与 users 表**，且**每档均为独立一轮**，避免历史数据抬高延迟。

## 4. 测试结果

### 4.1 CCU 阶梯（新机制：消息级 flush，均带登出，hold 60s）

| 目标 CCU | 登录成功 | 登录失败 | 保持 CCU | 保持掉线 | 登出失败 | 登录 P50 | 登录 P95 | Logic RSS | Login RSS | Gateway RSS |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2,000 | 2,000 | 0 | 2,000 | 0 | 0 | 9.38 ms | 639 ms | 1,127 MB | 864 MB | 1,271 MB |
| 8,000 | 8,000 | 0 | 8,000 | 0 | 0 | 5.20 ms | 127 ms | 1,369 MB | 866 MB | 1,422 MB |
| 12,000 | 12,000 | 0 | 12,000 | 0 | 0 | 5.19 ms | 810 ms | 1,428 MB | 862 MB | 1,518 MB |
| 16,000 | 16,000 | 0 | 16,000 | 0 | 0 | 5.15 ms | 236 ms | 1,527 MB | 867 MB | 1,723 MB |
| **20,000** | **20,000** | **0** | **20,000** | **0** | **0** | **21.37 ms** | **394 ms** | **1,552 MB** | **891 MB** | **1,785 MB** |
| 30,000 | 30,000 | 0 | 30,000 | 0 | 314 | 273.00 ms | 3,510 ms | 1,606 MB | 892 MB | 1,899 MB |

- **2,000~20,000 CCU 全链路零失败**：登录成功 = 目标 CCU，保持期间零掉线，登出零失败。
- 20,000 CCU 三服 RSS 合计约 **4.2 GB**（8G 规格内存余量约 2 倍）。
- 30,000 CCU 仍能连上并全部登录成功、保持 30,000，但**延迟显著劣化**（P50 273 ms、P95 3.5 s，逼近 3s 默认 deadline），登出出现 314 例（~1%）客户端超时——已到临界区。

### 4.2 落库正确性

| 目标 CCU | 落库错误日志 | `sync buffer full` | `retry exhausted` | DB 行数 | 其中 `online = 0` | 测试后 Redis 残留 |
| --- | --- | --- | --- | --- | --- | --- |
| 16,000 | 0 | 0 | 0 | 16,000 | 16,000 | 7 |
| 20,000 | 0 | 0 | 0 | 20,000 | 20,000 | 6,356 |
| 30,000 | 0 | 0 | 0 | 30,000 | 30,000 | 14,831 |

- 每一档 DB 行数 = 目标 CCU 且 `online` 全为 0，**登出落库 100% 覆盖，无数据丢失**，Logic 日志无任何落库错误。
- 残留 Redis 键全部为 `playerActor:*` 玩家缓存，非脏数据，见 2.1 的兜底语义。

### 4.3 残留缓存根因（设计内兜底，非缺陷）

- 登出时 `beginDestroy` 把 `online=0` 直接落库，并 `persistAsync(() -> deletePlayerCache)`：
  **仅当 DB 全部确认写入才删缓存**。
- 高 CCU 下登出为瞬时突发，db-io 池（8 线程 / 队列 1024，硬编码）被瞬间打满，
  部分模型写入被 `RejectedExecutionException` 拒收，降级到有界缓冲（**接受成功故不打错误日志**）
  并返回 `false` → 不触发删缓存 → 该玩家 Redis 副本保留。
- 有界缓冲随后定时批写，最终把 `online=0` 落库（故 DB 全量正确），Redis 副本则作为兜底保留。
- 现象：16,000 档残留 7（几乎无池饱和）；20,000 档 6,356；30,000 档 14,831（约一半）——
  与 db-io 池压力正相关。**不影响正确性**（下次登录会用 Redis 副本或 DB 数据重建）。
- 如需彻底消除残留，可调大/配置化 db-io 池（见第 7 节）。

### 4.4 与旧机制对比（同一环境、同负载）

| 目标 CCU | 旧「每次 setProperty 写 Redis」登录成功 | 新「消息级 flush」登录成功 |
| --- | --- | --- |
| 2,000 | 2,000（P50 1,865 ms） | 2,000（P50 9.38 ms） |
| 8,000 | 2,566（P50 2,469 ms） | 8,000（P50 5.20 ms） |
| 12,000 | 7,491（P50 4,415 ms） | 12,000（P50 5.19 ms） |

- 旧机制登录一次触发 4~5 次 `setProperty`，每次在 player-dispatcher 线程做 1 次**阻塞** Redis 往返，
  8 线程下服务时间由 ~20 ms 增至 ~32 ms，越过 250 次/s 排队临界点后延迟被急剧放大。
- 改为消息级 flush 后，一次登录只在消息末尾写 **1 次** Redis，登录 P50 回到 5 ms 量级，
  承载能力从旧机制的 ~7,000 提升到 **20,000+**。

## 5. 新发现：3s 默认截止时间导致静默丢消息（重要）

- [RequestContext.java](../../../../xs-net/xs-net-message/src/main/java/com/xs/net/message/RequestContext.java#L33) 定义
  `LEGACY_DEFAULT_TIMEOUT_MS = 3_000L`：客户端消息未携带 deadline 时，由网关赋值为 `now + 3s`。
- Logic 的 `RpcActor.onNetMessage` 在解码信封时若发现 `context.expired()`，**直接丢弃该消息**
  （`reason=deadline_expired`，仅打 WARN + 指标，不回复客户端）。
- 本机制下 20,000 CCU 的登录 P95 已回落到 394 ms（远低于 3s），未触发丢弃；
  但 30,000 CCU 的 P95 达 3,510 ms，已进入该风险区，是 30,000 档登出超时的诱因之一。

建议：为登录等长耗时消息显式设置足够 deadline，或将该默认值改为可配置。

## 6. 结论

1. **最终机制达成目标**：在线期间每条 Actor 消息处理完一次性 flush 脏字段到 Redis，
   登出落库、DB 确认后才清缓存；落库错误 0，登出落库 100% 覆盖，无数据丢失。
2. **8G 分配（3g/4g/1g）下，约 20,000 CCU 可稳定承载**：全链路零失败，登录 P50 ~21 ms，
   三服 RSS 合计约 4.2 GB，内存余量约 2 倍。
3. **约 30,000 CCU 进入临界区**：仍能全部登录与保持，但登录 P95 升至 3.5 s、登出约 1% 超时。
4. 高 CCU 下的失败主因是 **3s 默认截止时间**与 db-io 池在登出突发时的饱和，均已定位并有对应优化项。

## 7. 遗留问题与建议

| 问题 | 位置 | 建议 |
| --- | --- | --- |
| db-io 池在登出突发时饱和，导致缓存残留（数据仍正确） | [LogicIoExecutors.java](../../../../game-server/game-node-modules/src/main/java/com/game/logic/context/LogicIoExecutors.java) | 调大并配置化线程数/队列 |
| 3s 默认请求 deadline 静默丢消息 | [RequestContext.java](../../../../xs-net/xs-net-message/src/main/java/com/xs/net/message/RequestContext.java#L33) | 登录类消息显式设 deadline，或将该默认值配置化 |
| 登录/登出吞吐受限于玩家线程池 | Logic `application.conf` `player-dispatcher` 默认 8 | 按核数调大 `XS_PLAYER_POOL` |
| 压测端每连接一线程 | [Program.cs](../../../../xs-net/xs-net-sdk/src/main/sdk/unity/LoadTest/Program.cs) | 3 万以上连接客户端自身成为瓶颈，需多机压测区分 |

## 8. 复现步骤

```powershell
# 基础设施（WSL Docker）
docker run -d --name xs-net-pg-test -p 15432:5432 -e POSTGRES_USER=root -e POSTGRES_PASSWORD= `
  -e POSTGRES_DB=gamedb -e POSTGRES_HOST_AUTH_METHOD=trust `
  -v /mnt/f/project/xs-net/deploy/pg-init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro postgres:16
docker run -d --name xs-net-redis-test -p 16379:6379 `
  -v /mnt/f/project/xs-net/deploy/redis.conf:/etc/redis/redis.conf:ro redis:7 `
  redis-server /etc/redis/redis.conf --requirepass 123456
# 压测前清空历史数据（否则登录延迟被抬高，触发 3s deadline 丢弃）
docker exec -i xs-net-redis-test redis-cli -a 123456 FLUSHALL
docker exec -i xs-net-pg-test psql -U root -d gamedb -c "truncate table users;"

# 构建（JDK 21）
$env:JAVA_HOME='F:\Program Files\JetBrains\IntelliJ IDEA 2025.1.1\jbr'
.\mvnw.cmd install -DskipTests
.\scripts\Generate-Sdks.ps1 -Only Unity
dotnet build xs-net/xs-net-sdk/target/code/unity/LoadTest/XsNet.LoadTest.csproj -c Release

# 压测（保持连接 + 登出）
$env:DOTNET_ROLL_FORWARD='LatestMajor'
dotnet xs-net/xs-net-sdk/target/code/unity/LoadTest/bin/Release/net8.0/XsNet.LoadTest.dll `
  --host 127.0.0.1 --protocol tcp --port 8082 --count 20000 --concurrency 20000 --qps 500 `
  --player-start 9000000 --secret "<JWT_SECRET>" --timeout-ms 40000 --hold-seconds 60 --logout
```
