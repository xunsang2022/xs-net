# 改造计划书：移除 jprotobuf，迁移原生 Protobuf

> 日期：2026-08-14  
> 适用范围：`F:\project\xs-net`（`xs-net` 框架 + `game-server` 业务工程）  
> 主线方案（已与需求方确认）：先用现有 `tool-proto` 从 `@ProtobufClass` Java 类自动生成 `.proto`，字段号保持原样，再切换到原生 protoc 生成 Java 类，机械替换 jprotobuf 运行时编解码。

## 1. 背景与目标

当前仓库同时存在两套 Maven 工程：

- `xs-net/`：框架层，包含 `xs-net-core`、`xs-net-message`、`xs-net-tools`、`xs-net-all`。
- `game-server/`：基于 Spring Boot + Akka 的业务工程，依赖 `xs-net-all`。

消息序列化目前使用百度 jprotobuf：

- 消息类用 `com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass` 注解标记；
- 运行时用 `ProtobufProxy.create(...)` 反射生成编解码器；
- `xs-net-message`、`xs-net-core`、`xs-net-tools`、`game-server` 都声明了 jprotobuf 依赖。

改造目标：

1. 全仓库不再依赖 jprotobuf；
2. 协议改为 proto3 `.proto` 文件 + protoc 生成 Java 类，`.proto` 成为协议唯一事实源；
3. 网络帧格式、Akka 序列化行为、RPC 消息 ID 保持不变；
4. 业务消息字段号与 jprotobuf 当前反射顺序一致，最大限度保证二进制兼容。

> 说明：需求方提供的“三条链路”参考（`protobuf-message`、`nodeprotobuf`、`protobuf-bc` Gradle 模块）不在当前工作区，本计划以当前仓库实际代码为准。

## 2. 现状梳理

### 2.1 jprotobuf 使用点

| 模块/文件 | 用途 |
| --- | --- |
| `xs-net/xs-net-tools/pom.xml` | 声明 `jprotobuf.version=2.4.23`，并在 dependencyManagement 托管 |
| `xs-net/xs-net-tools/tool-proto/pom.xml` | 依赖 jprotobuf；生成器读取 `@ProtobufClass` |
| `xs-net/xs-net-tools/tool-proto/.../ProtoJavaAnalyse.java` | 扫描 `@ProtobufClass + @ProtoFileMerge` 并分析字段 |
| `xs-net/xs-net-message/pom.xml` | 依赖 jprotobuf |
| `xs-net/xs-net-message/.../NetMessage.java` | `@ProtobufClass` 网络信封类 |
| `xs-net/xs-net-message/.../MessageAble.java` | `ProtobufProxy.create(...)` 运行时编解码 |
| `xs-net/xs-net-message/.../serializer/NetMessageSerializer.java` | Akka 自定义序列化器（identifier=70），调用 `MessageAble` |
| `xs-net/xs-net-core/pom.xml` | 依赖 jprotobuf；`AkkaKit`、`NodeActor` 通过 `MessageAble` 收发 |
| `game-server/pom.xml` | 声明 `jprotobuf.version` 属性 |
| `game-server/game-message-action/.../action/*.java` 等 | 15 个左右的 `@ProtobufClass` 业务消息类 |

### 2.2 当前消息链路

网络帧格式（`MessageAble.toNetMessage` / `toNetByteMsg`）：

```text
packetLength(int) | rpcNum(int) | errorCode(int) | body
```

`body` 是 jprotobuf 编码的 `NetMessage`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rpcNum` | int | RPC 编号 |
| `data` | byte[] | 业务消息体 |
| `errorCode` | int | 错误码 |
| `sessionId` | int | 会话 ID |
| `userId` | int | 用户 ID |
| `userIp` | String | 用户 IP |

Akka 传输：

1. `AkkaKit.tell(...)` 对非本节点消息调用 `MessageAble.toNetMessage(message)`，把业务 DTO 编码进 `NetMessage`；
2. Akka 使用 `NetMessageSerializer` 把 `NetMessage` 编码为上述帧字节；
3. 接收端 `NodeActor`/`NetMessageSerializer` 反序列化，`MessageAble.toActionMessage` 按 `rpcNum` 找到消息类并 `ProtobufProxy.decode`；
4. 业务 Actor 以标记接口（`PlayerMessage`/`C2S`/`S2C`/`XSSystem`）为 Akka 消息类型，`receiveBuilder().onMessage(具体DTO.class, handler)` 分发。

`NodeActor` 启动时扫描 `@ActionMessage` 注解，把 `value()` 写入 `MessageAble.rpcMap`；业务 DTO 同时携带 `@ActionMessage` 与 `@ProtoFileMerge`。

### 2.3 当前业务消息清单（RPC 编号以代码为准）

| RPC | 消息类 | proto 包（来自 `@ProtoFileMerge`） | 说明 |
| --- | --- | --- | --- |
| 999 | `LogRecordMessage` | `pb.log` | 内部日志消息 |
| 1000 | `CS_LOGIN` | `pb.user` | 登录请求 |
| 1001 | `CS_Logout` | `pb.user` | 登出请求 |
| 1002 | `SC_LOGIN` | `pb.user` | 登录响应 |
| 1003 | `SC_Logout` | `pb.user`（fileName 为 `CS_Logout`） | 登出响应 |
| 2000 | `CS_BACKPACK_INFO` | `pb.backpack` | 背包查询 |
| 2001 | `SC_BACKPACK_INFO` | `pb.backpack` | 背包响应 |
| 2002 | `CS_USE_ITEM` | `pb.backpack` | 使用道具 |
| 2003 | `SC_USE_ITEM` | `pb.backpack` | 使用道具响应 |
| 2100 | `CS_LOG_REPORT` | `pb.log` | 日志上报 |
| 2101 | `SC_LOG_REPORT` | `pb.log` | 日志上报响应 |
| 3000 | `BackpackInitMessage` | `pb.backpack` | 背包初始化 |
| 3001 | `BackpackInitAckMessage` | `pb.backpack` | 背包初始化确认 |
| 3002 | `BackpackCleanupMessage` | `pb.backpack` | 背包清理 |

另有纯数据结构：

- `BackpackItemInfo`：仅有 `@ProtobufClass`，无 `@ProtoFileMerge`，被 `SC_BACKPACK_INFO` 引用；
- `NetMessage`：框架信封，仅有 `@ProtobufClass`。

### 2.4 `tool-proto` 能力与缺口

已有能力：

- 扫描同时带 `@ProtobufClass` 与 `@ProtoFileMerge` 的类；
- Java 基础类型到 proto 类型的映射（`int32/int64/float/double/bool/string/bytes`）；
- `List<T>` → `repeated`，`Map<K,V>` → `map`；
- 枚举生成与 `EnumReadable` 自定义值；
- 按 `@ProtoFileMerge` 合并文件并输出 `syntax = "proto3"` 的 `.proto`。

缺口：

1. 过滤条件要求两个注解同时存在，`BackpackItemInfo`、`NetMessage` 需要补注解或手工编写 proto；
2. 生成文件不带 `option java_package` / `option java_multiple_files`，跨文件引用需要人工整理；
3. 生成文件头部带时间戳，直接提交会造成噪音；
4. `tool-proto` 自身依赖 jprotobuf，最终态需要去依赖或改造；
5. 字段编号按反射字段顺序自 1 递增，必须用字节级对比验证与 jprotobuf 实际编号一致。

## 3. 目标架构

```text
*.proto (唯一协议源)
   │  Maven protoc 插件
   ▼
protoc 生成 Java 类 (MessageLite)
   │
   ├── MessageAble / NetMessageSerializer：parseFrom / toByteArray
   ├── RpcMessageRegistry：rpcNum → 消息类 + 路由角色
   └── 业务 Actor：直接使用生成类，构造走 Builder
```

关键约定：

- 消息 ID 不变，写入 `RpcMessageRegistry`；
- 字段号不变，与 jprotobuf 反射顺序一致；
- 帧格式不变；
- Akka `NetMessageSerializer` identifier 保持 70；
- 所有模块不再出现 `com.baidu.bjf.*` 与 `jprotobuf` 依赖。

## 4. 改造步骤

### 阶段 0：建立兼容性基线

1. 为所有 `@ProtobufClass` 业务消息与 `NetMessage` 编写一次性基线样例（覆盖普通字段、`List`、`Long`、`String`、空对象）。
2. 用 jprotobuf 编码并保存 hex 字节到测试资源或临时数据目录，作为迁移后的对比基准。
3. 先跑一遍当前构建基线：`mvn -pl xs-net/xs-net-message,xs-net/xs-net-tools/tool-proto -am test`（如 Maven 可用），确认改造前可编译。

### 阶段 1：生成 `.proto`

1. 给 `BackpackItemInfo`、`NetMessage` 补 `@ProtoFileMerge`：

   ```java
   @ProtobufClass
   @ProtoFileMerge(fileName = "BackpackItemInfo", filePackage = "pb.backpack")
   public class BackpackItemInfo { ... }
   ```

   ```java
   @ProtobufClass
   @ProtoFileMerge(fileName = "NetMessage", filePackage = "pb.net")
   public class NetMessage implements INetMessage { ... }
   ```

2. 运行 `tool-proto`（`ProtoGenerator` 或 `Main`），扫描以下包并输出到 `game-server/game-message-action/src/main/proto` 与 `xs-net/xs-net-message/src/main/proto`：

   - `com.game.message.action`
   - `com.game.message.actor`
   - `com.game.message.actor.logic`
   - `com.xs.net.message`

3. 人工整理生成结果：

   - 去掉文件头时间戳；
   - 给每个 `.proto` 增加：

     ```proto
     option java_multiple_files = true;
     option java_package = "保留现有 Java 包名";
     ```

   - 修正跨文件 `import`；
   - 保留 `SC_Logout` 当前归入 `CS_Logout` 文件的既有分组（如需纠正另行确认）。

4. 逐条对比阶段 0 的 hex 基线，确认字段号一致后才进入下一阶段。

### 阶段 2：接入 Maven protoc 生成

1. 在 `xs-net/xs-net-message/pom.xml` 与 `game-server/game-message-action/pom.xml` 增加：

   - `protobuf-java`（版本 3.25.5，与现有依赖管理一致）；
   - Maven protoc 插件。推荐 `io.github.ascopes.protobuf-maven-plugin`（自动下载 protoc，Windows 友好）；如内网不便，用 `org.xolstice.maven.plugins:protobuf-maven-plugin` + os-maven-plugin 并配置本地 protoc。

2. 配置 `src/main/proto` 作为 proto 源码目录，生成物输出到 `target/generated-sources/protobuf/java`，不提交生成类。
3. 编译验证生成类的包名、类名与现有 DTO 一致（如 `com.game.message.action.CS_LOGIN`、`com.xs.net.message.NetMessage`）。

### 阶段 3：替换运行时编解码

1. 重写 `MessageAble`：

   - 删除 `ProtobufProxy`；
   - `NetMessage` 改为生成类，`parseFrom/toByteArray`；
   - 业务消息按注册表取生成类，`parseFrom/toByteArray`；
   - 帧组装逻辑保持 `packetLength | rpcNum | errorCode | body`。

2. 保持 `NetMessageSerializer` 对外行为与 identifier 不变。
3. 新增 `RpcMessageRegistry`，把“rpcNum → 生成消息类 + 路由角色”集中管理，替代 `@ActionMessage` 反射扫描。

### 阶段 4：消息注册与路由（关键决策）

生成类无法实现现有标记接口，因此二选一：

方案 A（推荐）：Actor 命令类型改为 `MessageLite`

- 业务 Actor 改为 `AbstractBehavior<MessageLite>`，`onMessage(pb.xxx.CS_LOGIN.class, ...)`；
- `AkkaKit.tell` 改为按注册表中的角色/EntityTypeKey 发送；
- `NodeActor` 从 `RpcMessageRegistry` 初始化 sharding 与序列化注册；
- 标记接口（`C2S`/`S2C`/`PlayerMessage`/`XSSystem`）收敛为角色配置或删除。

优点：最终态干净，没有双重类型；缺点：`NodeActor`、`AkkaKit` 和 3 个业务 Actor 需要同步调整。

方案 B：保留标记接口 + `RpcEnvelope`

- 引入 `RpcEnvelope implements C2S/S2C/PlayerMessage/XSSystem`，携带 `rpcNum` 与 `MessageLite payload`；
- Actor 保持 `AbstractBehavior<标记接口>`，收到 envelope 后按 rpcNum 分发；
- 框架改动较小，但业务 Actor 长期多一层分发。

推荐先按方案 A 设计；如需求方希望尽量少改业务 Actor，可改走方案 B。

### 阶段 5：业务调用点替换

删除旧 POJO 后，逐文件替换：

- import 改为生成类包名；
- `new CS_LOGIN(...)` 改为 `CS_LOGIN.newBuilder().setXxx(...).build()`；
- `List` 读取 `getItems()` → `getItemsList()`；
- `Long` 字段在 proto3 下 getter 返回原始 `long`，审计空值判断；
- 涉及文件：`BackpackActor`、`PlayerActor`、`PlayerzzActor`、`ChannelActor`、`LogActor`、`game-message-action/App.java` 等。

### 阶段 6：移除 jprotobuf

1. 删除全部 `@ProtobufClass` 注解及 `com.baidu.bjf.*` import。
2. 删除以下文件中的 jprotobuf 依赖与版本属性：

   - `xs-net/xs-net-message/pom.xml`
   - `xs-net/xs-net-core/pom.xml`
   - `xs-net/xs-net-tools/pom.xml`
   - `xs-net/xs-net-tools/tool-proto/pom.xml`
   - `game-server/pom.xml`

3. `tool-proto` 处置二选一：

   - 保留：把 `@ProtobufClass` 检测改为自有注解（如 `com.proto.generator.annotation.ProtoClass`），移除 jprotobuf 依赖；
   - 移除：从 `xs-net-tools/pom.xml` 的 modules 中摘除，或标记 deprecated 归档。

4. 清理 `tool-proto` 残留依赖（reflectasm、qdox、jctools 等视去留决定）。

### 阶段 7：测试与验证

新增测试：

1. 字节兼容测试：同对象 jprotobuf 基线 hex 与原生编码 hex 一致；
2. `MessageAble` 编解码 roundtrip；
3. `RpcMessageRegistry` 完整性（无重复、无遗漏、与 proto 清单一致）；
4. Akka `NetMessageSerializer` roundtrip；
5. 跨节点链路：login → logic → gateway 消息流转集成测试。

构建验证：

```powershell
mvn clean install
```

先跑针对性模块，最后跑完整构建；若网络受限导致 protoc 下载失败，使用本地 protoc 并说明验证到哪一步。

## 5. 兼容性策略

- 字段号：以 jprotobuf 反射顺序为基准，`tool-proto` 的 `order` 原样写入 `.proto`，字节对比兜底；
- 消息 ID：`@ActionMessage.value()` 不变，写入注册表；
- 帧格式：`packetLength | rpcNum | errorCode | body` 不变；
- 枚举：当前业务消息用 `int` 表达枚举，不引入 proto enum，避免 wire 差异；
- `Long`/`String` null 语义：proto3 无 null，现有 `BackpackItemInfo`、`LogRecordMessage` 等空值用法需逐点审计。

## 6. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 字段号与 jprotobuf 实际编号不一致 | 阶段 0 先保存 hex 基线，阶段 1 逐条对比，不一致即停 |
| 生成类无法实现标记接口，Actor 路由需要改 | 阶段 4 明确方案 A/B，先改框架再改业务 |
| Lombok 构造/Getter 与生成类 API 差异 | 阶段 5 集中替换，按编译错误逐点修 |
| protoc 下载受网络限制 | 本地 protoc 或 Maven 离线仓库；必要时申请网络权限 |
| `tool-proto` 输出时间戳/并发不稳定 | 一次性生成后人工收敛，不自动重复生成 |
| 中文注释编码损坏 | 生成器已按 UTF-8 读取；后续修改含中文文件必须用 `apply_patch`，不用 PowerShell heredoc 管道 |

## 7. 交付物与实施顺序

交付物：

1. 各模块 `.proto` 文件（唯一协议源）；
2. protoc 生成类（构建期生成，不提交）；
3. `RpcMessageRegistry` 与新的 `MessageAble`；
4. 更新后的 Actor 与业务调用点；
5. 移除 jprotobuf 后的 Maven 配置；
6. 兼容性与 roundtrip 测试；
7. 本计划书（存放于 `.codex`）。

建议实施顺序：

```text
阶段0/1（一次性基线 + proto 生成） → 阶段2（protoc 接入）
→ 阶段3/4（框架替换，可与阶段5 小步并行）
→ 阶段6（去 jprotobuf） → 阶段7（完整验证）
```

## 8. 待确认事项

1. 目标工程确认：以当前 `F:\project\xs-net` 为准；参考中的 Gradle 工程不在本工作区，不纳入本次计划。
2. 生成代码是否提交：本计划建议 `.proto` 提交、protoc 生成类不提交；如团队要求提交生成类请说明。
3. `tool-proto` 去留：保留并改为自有注解，还是迁移完成后移除。
4. 路由方案：方案 A（`MessageLite` 命令类型，推荐）还是方案 B（`RpcEnvelope` + 标记接口）。
5. 线上兼容：当前按“字段号不变、客户端不强制升级”处理；若客户端允许同时升级，可进一步清理字段命名等。
6. `.proto` 归属：本计划放在 `game-message-action/src/main/proto` 与 `xs-net-message/src/main/proto`；如希望独立 `game-protocol` 模块，另行确认。
