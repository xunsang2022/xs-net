# xs-net 产品规划：运营与保障工具链

> 目标：把 xs-net 从「能跑的通联框架」补齐为「可运营、可保障、可灰度、可追溯」的框架级产品。
> 文档性质：规划文档（非现状说明），逐项标注「仓库已有」与「需新做」，不虚构现有能力。
> 依据文档：`ARCHITECTURE.md`（Gateway/Login/Logic 三节点架构）、`docs/登录压测报告.md`、各节点 `application.yml`。

---

## 1. 背景与现状依据

xs-net 当前是一套三层长连接游戏后端框架：

- **Gateway**（game-node-gateway，Spring Boot）：Netty TCP 8082 / 客户端 UDP 2002 接入、JWT 校验、Akka 组播路由、S2C 写回。
- **Login**（game-node-login，Spring Boot）：JWT 签发与校验。
- **Logic**（game-node-modules，普通 Java 非 Spring）：`PlayerActor`（Cluster Sharded）、`DBSyncEngine`（批量 UPSERT 落库）、`LogConsumer`（Streams 消费写入 PG）。

从"框架产品"角度审视运营与保障能力，现状如下（全部为仓库已有事实）：

| 能力项 | 现状 | 判定 |
|---|---|---|
| 可观测性（指标） | Gateway / Login 暴露 Spring Boot Actuator（health/info/metrics/prometheus，见 `game-node-gateway` 与 `game-node-login` 的 `application.yml`）；Logic 注册了 `dbsync.flush.*` 与 `dbsync.dirty.size` Micrometer 指标（`LogicMetrics.java`） | **已有（部分）** |
| 压测/容量 | `docs/登录压测报告.md`：TCP ~850 登录/s、UDP ~1366 登录/s（本机单机基线） | **已有** |
| 基础设施编排 | `docker-compose.yml` 编排 PG(17)+Redis(7) 本地开发环境；`deploy/pg-init.sql`、`deploy/redis.conf` 初始化脚本 | **已有（本地开发级）** |
| 热更新 / 热加载 | 无脚本引擎、无编译期热替换、无配置热加载机制 | **缺失（需新做）** |
| GM / 后台管理 | 无管理控制台、无指令下发通道、无在线查询/修复入口 | **缺失（需新做）** |
| 监控告警 / 链路追踪 | 仅指标端点（可拉取 Prometheus），无接线 prometheus/Grafana，无告警规则，无分布式追踪 | **缺失（需新做）** |
| 部署编排 / 发布 | 仅 docker-compose 拉本地 dev 依赖；无生产编排、无优雅上下线编排、无灰度/回滚策略 | **缺失（需新做）** |
| 反作弊 / 风控 | 无 | **缺失（需新做）** |

> 结论：xs-net 的连接、路由、持久化主链路相对成熟，但"运营与保障"仅覆盖了"指标埋点 + 本地可跑"，距离可上线运营仍缺热更、GM、告警、部署、反作弊五块硬能力。

---

## 2. 五项能力的规划（按优先级排序）

> 优先级（P0 最高）：**热更 = GM = 监控告警 > 部署 = 反作弊**。
> 理由：热更与 GM 决定"能不能上线"，监控告警决定"上线后敢不敢发"，部署与反作弊决定"规模化质量上限"。

### 2.1 热更（热更新与热加载）

- **现状评估**：仓库无任何脚本引擎（Groovy/Nashorn/JShell）依赖；Logic 为普通 Java 装配，Actor 通过 `ActorRegisterManager` + `ClassScanner` 在**启动期**扫描注册（`xs-net-core`）；协议注册表 `RpcMessageRegistry` 编译期/启动期构建、不可变。当前改玩法逻辑 = 全量重发 + 重启节点。
- **目标方案**：
  1. **配置热加载**（P0）：将 `DBSyncConfig`、Redis 连接、日志级别、限流/开关类配置外置到 Redis 或配置文件并支持运行期 refresh，避免改阈值就得重启。
  2. **玩法脚本热更**（P1）：在 Logic 引入脚本层（**需新做**，选型倾向纯 Java 侧轻量脚本或 JVM 内嵌脚本引擎），支持"加载新版本玩法脚本 → 新旧版本并存 → 切换"的带版本热更，而非停服重启。
  3. **协议热更**（P1，可选远）：`RpcMessageRegistry` 当前不可变、跨节点强一致，热增删协议号需先打通"注册表演进"与"版本协商"，风险高，建议二期。
- **最小实现范围（P0 先行）**：
  - 配置外置 + `LogicContext` 增加运行期 `refresh` 通道（**需新做**）；
  - 日志级别运行期动态调整，无需重启（复用已有 logback + 新增管理入口，**需新做**）。
- **标注**：此能力全部 **需新做**；仓库已有仅相关支撑件（`LogicContext`、`PlayerActor`、`DBSyncEngine` 可作为挂载点）。

### 2.2 GM / 管理后台

- **现状评估**：无任何 GM 指令、管理控制台、在线数据查询或运营操作入口；运维只能改 DB / 改代码重启（字段可见 `PlayerState`、`RedisStore` 等，但均无线上管理界面）。
- **目标方案**：
  1. **GM 指令通道**（P0）：建立**加密鉴权的管理面**（独立端口或独立管理 Actor），复用现有 Akka Cluster / `RpcActor` 投递机制，向 `PlayerActor` 下发目标指令（改属性、发物品、踢人、封禁查询/解除等，**需新做**）。
  2. **GM Web 控制台**（P1）：新做一个运营后台 Web（登录鉴权 + 指令面板 + 在线玩家查询 + 封禁管理），通过管理面网关接入 Logic（**需新做**）。
  3. **运营中间态**（P1）：在线查询玩家 Redis/DB 状态、跑玩家数据修复、批量公告等（**需新做**）。
- **最小实现范围（P0 先行）**：
  - 复用 `xs-net-message` 的注册表与 Protobuf 信封，新增一组 `GM_*` RPC（纯下行/上行管理消息）与一个 `GMActor` 实体（**需新做**）；
  - 管理面单独鉴权（独立令牌/独立 JWT 声明），与玩家通道隔离，不改动 `AuthHandler` 玩家路径（**需新做**）。
- **标注**：全部 **需新做**；叠加复用已有路由（`ChannelRoutable`/`PlayerRoutable`）、`xs-net-core` `RpcActor` 封装。

### 2.3 监控告警与可观测性

- **现状评估**：
  - **指标（已有）**：Gateway/Login 已暴露 Actuator `prometheus` 端点；Logic 注册 `dbsync.flush.duration`、`dbsync.flush.failures`、`dbsync.dirty.size`（`LogicMetrics.java`）。
  - **缺失**：无 Prometheus 抓取配置、无 Grafana 看板、无告警规则；无日志聚合（logback 仅本地文件）；无分布式追踪（Akka/Netty 跨节点链路无 traceId 贯穿）。
- **目标方案**：
  1. **指标采集与告警**（P0）：接入 Prometheus 抓取三节点 + 初版告警规则（`dbsync.dirty.size` 阈值、认证失败率、连接数、`flush.failures` 等，**需新做**）。
  2. **日志聚合**（P1）：引入日志采集（结构化 JSON + 采集转发到集中存储），支撑按玩家/节点/pipeline 检索（**需新做**）。
  3. **分布式追踪**（P2）：为「客户端 → Gateway → Logic → DB/Redis」注入统一 traceId/span 贯穿（**需新做**）。
- **最小实现范围（P0 先行）**：
  - 补一组**业务核心指标**到 Gateway 与 Logic（当前 Gateway 指标偏框架，缺认证成功率、连接数、在线数这类运营指标，**需新做**）；
  - 提供 Prometheus scrape 配置示例 + 首批告警规则文件 + Grafana 一页总览（**需新做**，注意：现有 `docker-compose.yml` 仅编排 PG/Redis，需扩展或新增 `prometheus`/`grafana` 服务）。
- **标注**：指标埋点**已有**；采集/告警/看板/追踪/日志聚合全部 **需新做**。

### 2.4 部署编排与发布

- **现状评估**：`docker-compose.yml` 只拉起 PG/Redis 本地依赖；三个节点仅能手动/脚本启动，无容器化镜像、无编排（K8s/Swarm）、无健康检查接入发布流程、无灰度/回滚。
- **目标方案**：
  1. **节点容器化 + 编排**（P1）：为 Gateway/Login/Logic 提供容器镜像与编排模板（优先 K8s，StatefulSet 化 Akka 节点 + 服务发现 + 优雅终止），配置走 ConfigMap/Secret 而非明文（**需新做**）。
  2. **优雅上下线**（P1）：支撑「摘流 → 排空 → 下线」的滚动发布，配合 Akka Cluster 离开与 `DBSyncEngine` 落空（**需新做**，需与热更/GM 的运维面协同）。
  3. **绿色多开 / 版本回滚**（P2）：多集群并联与一键回滚（**需新做**）。
- **最小实现范围（P0/P1）**：
  - 三节点 `Dockerfile` + 一套 K8s manifest/yaml 模板 + 探活（复用已有 Actuator `/actuator/health/liveness|readiness` 探针）（**需新做**；探针端点**已有**）。
- **标注**：探针端点与本地 compose **已有**；镜像、编排、优雅上下线、灰度回滚全部 **需新做**。

### 2.5 反作弊 / 风控

- **现状评估**：无任何反作弊能力；仅网关有基础连接约束（`max-connections-per-account`、超时踢出、JWT 校验，见 gateway `application.yml`），属接入限流而非风控。
- **目标方案**：
  1. **行为风控基线**（P1）：基于 `LogConsumer` 已落库的日志事件，做频率异常检测（如登录/操作频次突增、协议调用异常分布，**需新做**）。
  2. **规则与处置联动**（P2）：建立风控规则引擎 + 与 GM 封禁/踢人指令联动，形成"检测 → 告警/自动处置"闭环（**需新做**）。
- **最小实现范围（P0/P1）**：
  - 在网关/Love 热路径采样客户端行为事件写入 Streams（复用 `LogConsumer` 链路，事件埋点**需新做**，消费管线**已有**）；
  - 初版离线风控分析 + 告警（接入 2.3 告警通道）。
- **标注**：全链路 **需新做**；仅底层日志消费管线（`LogConsumer` + Redis Streams + PG）**已有**可复用。

---

## 3. 优先级总览

| 优先级 | 能力 | 判定 | 依赖 |
|---|---|---|---|
| **P0** | 热更（配置热加载/日志动态级别） | 需新做 | 挂载 `LogicContext` |
| **P0** | GM 指令通道（管理面 RPC + GMActor） | 需新做 | 复用 `xs-net-message`/`RpcActor` |
| **P0** | 监控告警（Prometheus 采集 + 首批规则 + 看板） | 指标已有、接线需新做 | 复用 `/prometheus` 端点 |
| **P1** | 部署编排（镜像 + K8s + 优雅上下线） | 探针已有、编排需新做 | 依赖 2.1/2.2 运维面 |
| **P1** | 反作弊（事件埋点 + 离线风控） | 需新做 | 复用 `LogConsumer` |

> 建议交付节奏：先 P0 三项（热更基础 + GM 指令 + 监控告警），使其满足"可灰度上线并获知状态"；随后 P1 部署编排与反作弊，支撑规模化运营。

---

## 4. 里程碑

| 里程碑 | 内容 | 产出 | 判定标准 |
|---|---|---|---|
| M0（已有基线） | 三节点连通、登录闭环、指标端点、压测报告 | — | 压测报告记录在案（TCP ~850/s，UDP ~1366/s） |
| M1（P0-A） | 配置热加载 + 日志动态级别；GM 指令通道（改属性/踢人/封禁查询） | 热更基础落地；`GMActor` + 一组 GM RPC | 不重启改配置生效；GM 指令由 `PlayerActor` 正确执行并落库 |
| M2（P0-B） | Prometheus 采集三节点 + 首批告警规则 + Grafana 单页看板 | 接线配置 + 告警规则文件 + 看板 | 压测时看板/告警可见 `dbsync.*`、认证成功率、连接数 |
| M3（P1-A） | 三节点 Docker 镜像 + K8s 模板 + 优雅上下线 | 仓库 `deploy/` 下新增编排产物 | 滚动发布可摘流排空无丢状态、探针准出 |
| M4（P1-B） | 客户端行为事件采样 + 离线风控分析 + 告警 | 风控事件流水 + 规则 | 异常频次可触发告警并与 GM 封禁联动 |
| M5（P2） | 分布式追踪；协议热更；多集群灰度/回滚 | 全链路 traceId 贯穿 + 版本演进 | 跨节点错误可一根链路定位 |

> 标注：M0 为仓库已有基线；M1–M5 全部 **需新做**。

---

## 5. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **Logic 非 Spring Boot**：热更/配置刷新/监控装配无 Spring 生态可借，`LogicMetrics` 也仅靠 `GameLogicApplication` 手动装配 | 热更与配置刷新实现成本高、易碎 | 优先外置纯 Java 侧刷新机制；剧本脚本层做隔离；保持 `game-node-modules` 不引入 Spring Boot 的约束 |
| **`RpcMessageRegistry` 不可变**：协议表启动期构建、跨节点一致，热增删协议号与"协议热更"冲突 | 协议热更需版本协商与演进，风险高 | 二期再做；一期只用配置/脚本热更，不触碰协议表演进 |
| **管理面安全**：GM 通道一旦被攻破等于直接控制玩家数据 | 数据/经济被破坏 | 管理面独立强鉴权 + 与玩家通道隔离 + 审计日志；上线前做鉴权加固 |
| **优雅上下线与落库一致性**：Akka 排空期间 `DBSyncEngine`/`LogConsumer` 若没落空丢状态 | 更新丢数据 | 编排先"摘流→排空落库→再离线"，配 Actuator readiness 探针 |
| **监控告警过度/噪音**：`dbsync.dirty.size` 等指标阈值不当触发误报 | 信任下降 | 经压测基线（登录压测报告）校准阈值后再上线规则 |
| **反作弊误杀**：频次规则过严误封正常玩家 | 留存受损 | 先"只告警不处置"，规则充分后接入 GM 自动处置 |

---

## 6. 附录：仓库已有的运营相关资产（勿误判为新做）

- 指标埋点：Gateway/Login Actuator `health,info,metrics,prometheus`（`game-node-gateway/application.yml`、`game-node-login/application.yml`）；Logic `LogicMetrics.java`（`dbsync.flush.*`、`dbsync.dirty.size`）。
- 压测：`docs/登录压测报告.md`；压测端为 `xs-net-sdk` 客户端（原 `game-client` 已移除）。
- 接入层基础约束：网关 `max-connections-per-account`、认证超时、读空闲踢出、UDP 窗口/重传（`game-node-gateway/application.yml`）。
- 本地基础设施：`docker-compose.yml`（PG17+Redis7）、`deploy/pg-init.sql`、`deploy/redis.conf`。
- 消息注册与路由能力（GM/热更可复用的底座）：`xs-net-message`（`RpcMessageRegistry`/`NetMessage`/`RpcMessage`）、`xs-net-core`（`RpcActor`/`ActorRegisterManager`/`ClassScanner`）、`ChannelRoutable`/`PlayerRoutable`。

> 除上述明确清单外，热更、GM、告警采集、日志聚合、分布式追踪、部署编排、反作弊均为 **需新做**，本规划不虚构任何现有实现。