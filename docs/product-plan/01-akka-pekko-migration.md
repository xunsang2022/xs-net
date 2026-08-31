# xs-net Akka 2.8.5 → Apache Pekko 1.1.x 迁移产品优化规划

> 依据仓库当前事实盘点（ARCHITECTURE.md、application.* 配置、AkkaKit.java、GameFrameworkAutoConfiguration.java、各模块 pom.xml）。
> 本文仅描述迁移方案中可被仓库真实内容支撑的改动点，不包含猜测或编造的内容；目标版本选择（Pekko 1.1.x）为规划方向，具体明细以 Apache Pekko 官方发布物为准。

- 创建日期：2026-08-28
- 状态：规划草案

---

## 一、迁移目标与收益

| 目标 | 说明 |
|---|---|
| 摆脱商业许可约束 | Akka 自 2.7.x 起对核心 AKKA 相关类采取 **Business Source License (BSL)**，闭源商业部署存在合规风险；Apache Pekko 为 ASF 管辖的 **Apache-2.0** 开源许可，可继续闭源商用 |
| 语义等价、改动可控 | Pekko 1.1.x（Scala 2.13）保持与 Akka 2.8.x 同名、同包结构的类与 API（`org.apache.pekko.*`），本项目对 Akka 的使用集中且浅（typed ClusterSharding + Classic 辅助 + 配置字符串），迁移面收敛 |
| 生态延续 | 本项目依赖的 Artery（Aeron UDP）、Cluster Sharding、Cluster Typed、Serialization Jackson、SLF4J 在 Pekko 中均有对应模块 |
| 长期维护 | Apache 基金会治理下的活跃维护，安全补丁与版本演进可持续 |

> 说明：本仓库同时依赖 **3.x 的 Spring Boot activator（Spring 7 / Spring Boot 4.0.0）与 Akka 2.8.5 共存**，迁移 Pekko 不改动 Spring、Netty、Protobuf、Redis、PostgreSQL 等其它技术栈。

---

## 二、现状触点盘点（仅依据仓库真实内容）

### 2.1 模块与 Akka 坐标分布

按 `com.typesafe.akka` 坐标出现的 pom.xml 分类：

| 模块（pom.xml 路径） | 使用方式 | 用到的 Akka artifact |
|---|---|---|
| `xs-net/xs-net/pom.xml` | dependencyManagement 导入 BOM | `akka-bom_2.13`（版本 `${akka.version}`） |
| `game-server/pom.xml` | dependencyManagement 导入 BOM | `akka-bom_2.13`（版本 `${akka.version}`） |
| `xs-net/xs-net-core/pom.xml` | 直接依赖 | `akka-actor-typed_2.13`、`akka-cluster-sharding-typed_2.13` |
| `xs-net/xs-net-message/pom.xml` | 直接依赖 | `akka-actor_2.13`（显式版本 `2.8.5`） |
| `game-server/game-comment/pom.xml` | 直接依赖 | `akka-actor_2.13`、`akka-cluster-sharding-typed_2.13` |
| `game-server/game-node-gateway/pom.xml` | 直接依赖 | `akka-cluster_2.13`、`akka-remote_2.13`、`akka-slf4j_2.13`、`akka-serialization-jackson_2.13`、`akka-actor-typed_2.13`、`akka-cluster-typed_2.13`、`akka-cluster-sharding-typed_2.13`（均 `${akka.version}`） |
| `game-server/game-node-modules/pom.xml` | 直接依赖 | `akka-actor-typed_2.13`、`akka-cluster-typed_2.13`、`akka-cluster-sharding-typed_2.13`、`akka-remote_2.13`、`akka-slf4j_2.13`、`akka-serialization-jackson_2.13`（均 `${akka.version}`） |

版本属性定义位置：
- `xs-net/xs-net/pom.xml`：`<akka.version>2.8.5</akka.version>`
- `game-server/pom.xml`：`<akka.version>2.8.5</akka.version>`

关联的非 Akka 坐标（迁移时按 Pekko 对应要求核对可用性，不必改动其版本本身）：
- `io.aeron:aeron-driver`（`xs-net/pom.xml`、`game-server/pom.xml` 中管理为 `1.40.0`）—— Artery Aeron UDP 底层
- `org.lmdbjava:lmdbjava`（`xs-net/pom.xml`、`game-server/pom.xml` 管理为 `0.8.3`，`modules` 依赖）—— 分布式数据持久化

### 2.2 HOCON / YAML 配置触点

| 文件 | 现状（含 Akka 的字面量） |
|---|---|
| `game-server/game-node-gateway/src/main/resources/application.conf` | 根块 `akka {`；`actor.provider = "cluster"`；`actor.xs-entity-dispatcher`；`remote.artery`；`canonical { hostname/port = 2001 }`；`cluster.seed-nodes` 含 `"akka://game-system@127.0.0.1:2001"` 与 `"akka://game-system@127.0.0.1:4001"`；`cluster.roles = ["gateway"]` |
| `game-server/game-node-modules/src/main/resources/application.conf` | 根块 `akka {`；`actor.provider = "cluster"`；`actor.xs-entity-dispatcher`；`remote.artery`；`aeron { dir / media-driver / socket }`；`canonical { hostname/port = 4001 }`；`cluster.seed-nodes`（`game-system@2001/4001`）；`cluster.roles = ["logic","system"]`；`cluster.sharding { numberOfShards=100, remember-entities=off, remember-entities-store="ddata" }`；`distributed-data.durable.store = "akka.cluster.distributed-data.DurableStore$None$"` |
| `xs-net/xs-net-message/src/main/resources/application.conf` | 根块 `akka {`；`loglevel/stdout-loglevel`；`actor.provider = "akka.remote.RemoteActorRefProvider"`；`actor.allow-java-serialization = off`；`actor.serializers.net = "com.xs.net.message.serializer.NetMessageSerializer"`；`serialization-bindings` |
| `game-server/game-node-gateway/src/main/resources/application.yml` | `logging.level.akka: INFO`（第 4 行）；HTTP `server.port: 8080`；`game.netty.port: 8082`；`game.udp.port: 2002` |
| `game-server/game-node-modules/src/main/resources/application.yml` | `game.akka { hostname: 127.0.0.1, port: 4001 }`（第 22–24 行）；Redis `game.redis.port: 6379` |
| `game-server/game-node-login/src/main/resources/application.yml` | `server.port: 8081`；无 Akka 相关键（Login 不涉 Akka，仅作端口触点记录） |

（端口触点汇总：Gateway HTTP 8080、Netty TCP 8082、UDP 2002、Artery canonical 2001；Logic Artery canonical 4001；Login HTTP 8081；Redis 6379。迁移不改动上述端口值，仅改动“akka→pekko”的配置命名空间。）

### 2.3 Java 代码触点

`xs-net/xs-net-core/src/main/java/com/xs/core/akka/AkkaKit.java`：
- import：`akka.actor.typed.ActorSystem`、`akka.actor.typed.Behavior`、`akka.actor.typed.Props`、`akka.actor.typed.javadsl.Behaviors`、`akka.cluster.sharding.typed.javadsl.ClusterSharding`、`Entity`、`EntityContext`、`EntityRef`、`EntityTypeKey`
- 类内常量：`public static final String ENTITY_DISPATCHER = "akka.actor.xs-entity-dispatcher";`
- 方法内引用：`akka.cluster.Cluster.get(system).selfRoles()`（`getCurrentNodeRoles`）

`xs-net/xs-net-core/src/main/java/com/xs/core/config/GameFrameworkAutoConfiguration.java`：
- import：`akka.actor.typed.ActorSystem`、`akka.cluster.sharding.typed.javadsl.ClusterSharding`
- 注解字符串类名：`@ConditionalOnClass(name = "akka.cluster.sharding.typed.javadsl.ClusterSharding")`、`@ConditionalOnClass(name = "akka.actor.typed.ActorSystem")`
- 方法体全限定类名：`akka.actor.typed.Behavior`、`akka.actor.typed.javadsl.Behaviors`、`ActorSystem.create(...)`

### 2.4 文档触点

- `ARCHITECTURE.md` 技术栈表含 `Akka | 2.8.5 | Cluster + Sharding，Artery/Aeron UDP`；架构图与正文中的 “Akka Cluster”、“Akka Cluster Sharding” 措辞。

---

## 三、改动触点清单

### 3.1 Maven 坐标（group `com.typesafe.akka` → `org.apache.pekko`，artifact `akka-*` → `pekko-*`）

| 位置 | 改动 |
|---|---|
| `xs-net/xs-net/pom.xml`、`game-server/pom.xml`（BOM） | `akka-bom_2.13` → `pekko-bom_2.13`；版本 `${akka.version}` → `${pekko.version}`（目标 1.1.x） |
| 各模块直接依赖的 artifact | `akka-actor_2.13`/`akka-actor-typed_2.13`/`akka-cluster_2.13`/`akka-cluster-typed_2.13`/`akka-cluster-sharding-typed_2.13`/`akka-remote_2.13`/`akka-slf4j_2.13`/`akka-serialization-jackson_2.13` → 对应 `pekko-*_2.13` 同名 artifact，group 换为 `org.apache.pekko` |
| `xs-net/xs-net-message/pom.xml` | 显式版本 `2.8.5` 需同步替换为目标 Pekko 版本号（当前其显式 `<version>` 与 `${akka.version}` 并存） |
| 版本属性 | 将两处 `<akka.version>` 属性更名为 `<pekko.version>` 或就地改写为目标版本 |

> 涉及坐标的模块 pom：`xs-net-core`、`xs-net-message`、`game-comment`、`game-node-gateway`、`game-node-modules`，以及两个 BOM 导入（`xs-net/pom.xml`、`game-server/pom.xml`）。Aeron、LMDB、Protobuf 等非 Akka 坐标按 Pekko 1.1.x 兼容性核对，不改变其坐标本身。

### 3.2 Java import（包前缀 `akka.*` → `org.apache.pekko.*`）

- `AkkaKit.java` 中 9 个 `akka.*` import 全部改为 `org.apache.pekko.*`；`akka.cluster.Cluster` 方法引用同步改为 `org.apache.pekko.cluster.Cluster`。
- `GameFrameworkAutoConfiguration.java` 中 `akka.actor.typed.ActorSystem`、`akka.cluster.sharding.typed.javadsl.ClusterSharding` import 及方法体内全限定类名全部改为 `org.apache.pekko.*`。

### 3.3 类名字符串 / 常量

| 文件 | 现状字面量 | 迁移后 |
|---|---|---|
| `AkkaKit.java` | `ENTITY_DISPATCHER = "akka.actor.xs-entity-dispatcher"` | `"pekko.actor.xs-entity-dispatcher"`（HOCON 根块同步改为 `pekko` 后生效） |
| `GameFrameworkAutoConfiguration.java` | `@ConditionalOnClass(name = "akka.cluster.sharding.typed.javadsl.ClusterSharding")`、`name = "akka.actor.typed.ActorSystem"` | 类名串改为 `org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding`、`org.apache.pekko.actor.typed.ActorSystem` |
| `modules/application.conf` | `distributed-data.durable.store = "akka.cluster.distributed-data.DurableStore$None$"` | `"pekko.cluster.distributed-data.DurableStore$None$"` |
| `message/application.conf` | `actor.provider = "akka.remote.RemoteActorRefProvider"` | `"pekko.remote.RemoteActorRefProvider"` |

### 3.4 集群协议标识（seed-nodes 的 actor system 名）

- `gateway/application.conf`、`modules/application.conf` 中 `cluster.seed-nodes` 数组的 `"akka://game-system@127.0.0.1:2001"` / `"akka://game-system@127.0.0.1:4001"`：Pekko ActorSystem 的默认 provider 协议名为 `pekko`（而非 `akka`），需同步替换 `akka://` → `pekko://`。**此改动要求迁移后的 Gateway 与 Logic 使用一致协议名，否则集群无法组成。**
> 本项以及 3.3 的 provider 字符串并非“我推测的版本号”，而是 Pekko 的既有命名约定，落地时以官方文档关于 actor system URL scheme 的说明为准。

### 3.5 HOCON 根块与 YAML 键

| 文件 | 改动 |
|---|---|
| `gateway/application.conf`、`modules/application.conf`、`message/application.conf` | 根块 `akka {` → `pekko {`，其下全部子键（actor/remote/cluster/sharding/distributed-data 等）保持不变 |
| `gateway/application.yml` | `logging.level.akka` → `logging.level.org.apache.pekko`（或对应 Pekko 日志包） |
| `modules/application.yml` | `game.akka { hostname, port }` —— 注意这是 **项目自定义的 game 命名空间**，非 Akka 内核配置，只是命名带了 `akka`；是否随迁移改名为 `game.pekko` 需与读取方 `GameLogicApplication` 保持一致（先确认该键被哪个类读取再定）。端口 6379/4001 值不变 |
| `xs-net-message` serializers | `serializers.net` 的 value 是项目自定义类 `com.xs.net.message.serializer.NetMessageSerializer`，不涉及包名变更，不改动 |

### 3.6 JVM 参数（`-Dakka.*` → `-Dpekko.*`）

- 现有 `game-server/game-node-gateway/src/main/resources/application.conf`、`game-node-modules/src/main/resources/application.conf` 注释中提及以 `-Dakka.remote.artery.canonical.port` 覆盖端口；Pekko 的系统属性根为 `-Dpekko.*`，工程内所有启动脚本 / IDEA Run Configuration / 文档中出现的 `-Dakka.*` 需改为 `-Dpekko.*`（本仓库未在源码目录发现硬编码 JVM 启动参数文件，需在部署侧/运行配置核对）。

### 3.7 文档

- `ARCHITECTURE.md`：技术栈表 `Akka 2.8.5` → `Apache Pekko 1.1.x`（/对应实际落版）；正文与结构图“Akka Cluster / Akka Cluster Sharding”措辞同步为 Pekko。
- 各 application.conf 顶部注释中“Akka Configuration / 用于配置Akka Cluster”等注释文字可顺带更新（非功能改动）。

---

## 四、执行顺序

> 依赖关系驱动：先换坐标与依赖树打通，再改源码与配置，最后改部署侧并验证。

1. **版本与坐标层**：在两个 BOM pom 与各模块 pom 中完成 `org.apache.pekko` 坐标替换 + 版本属性切换（全部 pom 一次性改完，保证整仓同一 Pekko 版本）。
2. **源码层（编译依赖）**：改 `AkkaKit.java`、`GameFrameworkAutoConfiguration.java` 的 import / 类名串 / 常量；`modules/application.conf` 的 `DurableStore$None$` 串、`message/application.conf` 的 `RemoteActorRefProvider` 串。
3. **配置层（运行时依赖）**：三个 application.conf 根块 `akka` → `pekko`；seed-nodes 协议 `akka://` → `pekko://`；两个 application.yml 的日志/自定义键处理。
4. **部署侧**：核对运行配置中的 `-Dakka.*` → `-Dpekko.*`；容器/发布脚本同步。
5. **文档层**：更新 `ARCHITECTURE.md` 技术栈与措辞。
6. **迁移窗口/兼容性验证**：因 Pekko 与 Akka 物理共存会产生不同 serialization 指纹与协议名，**不支持混合集群滚动升级**，按第 5 节做停机切换式验证。

---

## 五、验证计划

| 阶段 | 验证动作 | 判定标准 |
|---|---|---|
| 编译 | 对全部模块执行 Maven 编译（用仓库 `.\mvnw.cmd`，固定 JDK 21 与 `.m2/repository`） | 无 Akka/Pekko 相关编译错误；依赖树不再含 `com.typesafe.akka` |
| 单元/集成测试 | `.\mvnw.cmd test`（或受影响的模块针对性测试） | 全部通过；不再出现 `ClassNotFound`（尤以 `@ConditionalOnClass` 反射串为检查对象） |
| 单节点冒烟 | 启动 Login(8081)、Gateway(8080/8082/2002/Artery 2001)、Logic(Artery 4001) | HTTP/JWT、Netty TCP、UDP、Redis(6379) 链路正常；Pekko 日志正常输出 |
| 集群验证 | 两节点（gateway+logic）组成集群，确认 seed-nodes 用 `pekko://` 协议成功握手 | 无 HandshakeTimeout、节点状态 Up；ClusterSharding 分片（100）正常 rebalance |
| 序列化验证 | 验证 `NetMessageSerializer` 正常完成跨节点投递（PlayerMessage/S2C） | 上下行消息一致；`allow-java-serialization = off` 下仅走白名单序列化器 |
| 压测对比 | 参考迁移前 Akka 网络延迟/吞吐基线，对同一客户端-网关-logic 链路做压测 | 延时 P50/P99、吞吐量无明显恶化（同 Aeron UDP 传输） |
| 可观测性 | gateway `/actuator/health/liveness/readiness`、metrics/prometheus | 探活与指标端点正常 |

---

## 六、风险与回退

| 风险 | 影响 | 对策 |
|---|---|---|
| 配置命名空间整体变化 | `akka`→`pekko`、`akka://`→`pekko://`、`-Dakka`→`-Dpekko` 任何一处遗漏都会导致参数不生效或无法组集群 | 迁移后用“编译通过 + 集群冒烟”兜底；关键字面量通过搜索 `akka` 全部核对落点 |
| `@ConditionalOnClass` 反射字符串与类名不符 | Bean 不装配，ClusterSharding/ActorSystem 缺失导致启动失败 | 编译期无法发现，必须在单节点冒烟阶段显式验证 `ActorSystem`/`ClusterSharding` Bean 已创建 |
| protocol/serialization 指纹不兼容 | 迁移过程中 Akka 与 Pekko 节点共存会握手失败 | 不支持混合集群，采用整批切换；回退时整批回滚 |
| 版本属性两处并行维护 | xs-net 与 game-server 两组 BOM 版本不一致 | 统一改为一个版本常量来源，或两处同步替换 |
| 自定义 `game.akka` 命名键 | 若未同步读取方，Logic 启动读不到 hostname/port | 先定位 `GameLogicApplication` 的读取键再改名；或保守保留 `game.akka` 键名 |
| 非 Akka 依赖（Aeron/LMDB）与 Pekko 的兼容性 | 版本不符可能引发 Artery 异常 | 按 Pekko 1.1.x 官方兼容矩阵核对 `aeron-driver` 版本，必要时随迁移微调 |

### 回退策略

- **回退前基线**：迁移前先对 Akka 2.8.5 版本做编译、测试、集群冒烟与压测，留存基线数据与可用构建产物。
- **一键回退**：改动集中在 group/包前缀/配置命名空间，回退时 `git checkout` 涉及的 pom、Java、application.*、ARCHITECTURE.md 即可整体还原；因不支持混合集群，回退时全部节点同时切回 Akka 版本。
- **冻结窗口**：迁移提交在独立分支进行，合并前完成全部验证；回退不触碰 `io.aeron`、`lmdbjava` 及其它非 Akka 坐标。

---

## 附：涉及文件清单（迁移需改动，均已在仓库确认存在）

- `xs-net/xs-net/pom.xml`、`game-server/pom.xml`
- `xs-net/xs-net-core/pom.xml`、`xs-net/xs-net-message/pom.xml`
- `game-server/game-comment/pom.xml`、`game-server/game-node-gateway/pom.xml`、`game-server/game-node-modules/pom.xml`
- `xs-net/xs-net-core/src/main/java/com/xs/core/akka/AkkaKit.java`
- `xs-net/xs-net-core/src/main/java/com/xs/core/config/GameFrameworkAutoConfiguration.java`
- `game-server/game-node-gateway/src/main/resources/application.conf`、`application.yml`
- `game-server/game-node-modules/src/main/resources/application.conf`、`application.yml`
- `xs-net/xs-net-message/src/main/resources/application.conf`
- `ARCHITECTURE.md`