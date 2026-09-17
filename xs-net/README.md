# XS-NET（游戏服务器框架）

XS-NET 为游戏服务端提供 Akka Cluster Sharding、RPC 消息契约、Protobuf 信封及 SDK 生成基础设施。Gateway 的 Netty 网络接入属于 `game-server`，不在本框架模块内实现。

## 模块结构

```text
xs-net/
├── xs-net-core/              # Akka 封装、Actor 扫描与分片初始化
├── xs-net-message/           # NetMessage、RpcMessageSpec、注册表和编解码
├── xs-net-rpc-maven-plugin/  # RPC 与 SDK 生成插件
├── xs-net-sdk/               # SDK 打包模块
├── xs-net-tools/             # 通用工具与 LuBan 配置工具
└── xs-net-all/               # 框架聚合包
```

## 核心能力

### RPC 消息契约

`RpcMessageSpec` 描述一条协议的编号、Protobuf 类型、解析器、DTO 编解码器、目标角色、方向和路由要求。`RpcMessageRegistry` 提供按编号、Protobuf 类型和 DTO 类型的检索；`RpcMessageRegistryBuilder` 在注册时拒绝重复编号或类型。

`MessageAble` 在已初始化的注册表上完成 DTO、`RpcMessage` 与 `NetMessage` 的转换。跨节点的 Actor 消息以 `NetMessage` 序列化；本地分片则直接投递对象。

### Actor 与分片

- `RpcActor<M>` 是基于 Akka Typed `AbstractBehavior<M>` 的基类，保存实体 ID 和消息注册表，并提供 `sendRpc`；
- `AkkaKit` 负责初始化 Cluster Sharding、获取实体引用和投递消息；
- 节点启动时扫描其 Actor 包。节点拥有的角色初始化为本地实体分片；不拥有的角色初始化为远程代理分片。

远程投递的消息必须能转换为 `NetMessage`，以便 Akka 跨节点传输。

### SDK 与插件

`xs-net-rpc-maven-plugin` 根据 RPC 协议生成相关产物；`xs-net-sdk` 负责统一生成客户端代码。游戏侧的 Protobuf 协议位于 `game-server/game-message-action`，SDK 模板位于 `xs-net/xs-net-sdk/src/main/sdk`，生成结果位于 `xs-net/xs-net-sdk/target/code`。

## 使用的核心类型

| 类型 | 职责 |
|---|---|
| `NetMessage` | Protobuf 网络信封，包含 `rpcNum` 和 `data` |
| `RpcMessage` | 可路由 RPC 消息抽象 |
| `RpcMessageSpec` | 单个 RPC 协议契约 |
| `RpcMessageRegistry` | RPC 协议查询接口 |
| `RpcMessageRegistryBuilder` | 构建并校验不可变注册表 |
| `RpcPayloadCodec` | DTO 与 Protobuf payload 的双向编解码 |
| `RpcActor` | 支持 RPC 投递的 Akka Typed Actor 基类 |
| `AkkaKit` | Cluster Sharding 初始化与消息投递工具 |

## 构建

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl xs-net -am clean install
```

需要构建整个工作区时执行：

```powershell
.\mvnw.cmd clean install
```

仓库使用 JDK 21 和包装脚本定位的 Maven；不要依赖 PATH 中的全局 Maven。

## 依赖使用

框架聚合包的坐标为：

```xml
<dependency>
    <groupId>com.xs</groupId>
    <artifactId>xs-net-all</artifactId>
    <version>0.0.1</version>
</dependency>
```

实际版本应与当前 reactor 保持一致。

## Actor 示例

```java
public final class PlayerActor extends RpcActor<PlayerMessage> {
    private PlayerActor(ActorContext<PlayerMessage> context, String entityId) {
        super(context, entityId);
    }

    public static Behavior<PlayerMessage> create(String entityId) {
        return Behaviors.setup(context -> new PlayerActor(context, entityId));
    }
}
```

Actor 的消息接口需由节点角色配置和 RPC 注册表共同约束。完整的注册、分片和网关路由流程见 [消息注册与消息处理流程.md](../docs/消息注册与消息处理流程.md)。

## 注意事项

- 远程分片消息必须具有可用的 RPC 契约；
- 同一协议编号、Protobuf 类型和 DTO 类型不得重复注册；
- Gateway wire protocol 为 `4 字节大端长度前缀 + NetMessage protobuf bytes`，长度前缀不属于 `xs-net-message` 的跨节点消息转换；
- Logic 节点不使用 Spring Boot，Redis 与 Akka 由普通 Java 启动流程装配。
