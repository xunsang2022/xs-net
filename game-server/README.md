# Game Server（游戏服务器）

游戏服务端由 Gateway、Login 和 Logic 节点组成。Gateway 与 Login 使用 Spring Boot；Logic 是手动装配 Redis 与 Pekko 的普通 Java 进程，不使用 Spring Boot。

跨模块架构和数据流见 [../ARCHITECTURE.md](../ARCHITECTURE.md)。

## 模块

```text
game-server/
├── game-comment/          # JWT 等公共工具
├── game-message-action/   # Protobuf、DTO、路由接口和 RPC 注册表
├── game-node-gateway/     # HTTP 管理端点、Netty TCP 接入与 S2C 回写
├── game-node-login/       # JWT 签发和校验
└── game-node-modules/     # PlayerActor、Redis、DBSyncEngine、LogConsumer
```

## 服务职责

| 节点 | 职责 | 端口 |
|---|---|---|
| Gateway | 首帧 JWT 认证、Netty TCP/UDP 游戏消息接入、Pekko 路由、S2C 写回 | HTTP 8080、TCP 8082、客户端 UDP 2002、Pekko Artery 2001 |
| Login | JWT 签发与校验 | HTTP 8081 |
| Logic | 游戏状态、Redis、异步 PostgreSQL 持久化、日志消费 | Pekko Artery 4001 |

Gateway 不直连 Redis 或 PostgreSQL；Login 不创建玩家、不处理游戏状态；Logic 承担状态和持久化。

## 网络协议

客户端连接 Gateway TCP 8082 后，第一个帧必须是 UTF-8 JWT。认证成功后，游戏帧使用：

```text
len (4B, big-endian, 表示后续消息体字节数) | NetMessage protobuf bytes
                                                  ├─ rpcNum
                                                  └─ data
```

`NetMessage` 的 `rpcNum` 选择协议契约，`data` 是对应的 Protobuf payload。协议登记、注册和路由过程见 [../docs/消息注册与消息处理流程.md](../docs/消息注册与消息处理流程.md)。

## 配置

### Gateway 与 Login

Gateway 的 `application.yml` 默认监听 HTTP 8080、Netty TCP 8082；Login 默认监听 HTTP 8081。二者当前均含仅用于本地开发的 JWT 默认值。

生产环境必须在部署配置中覆盖 JWT 密钥，并确保 Gateway 与 Login 使用完全一致、长度至少 32 字节的密钥。Gateway 的 Netty 线程数、帧长度、认证超时和空闲超时位于 `game-node-gateway/src/main/resources/application.yml`。

### Logic

Logic 从 `game-node-modules/src/main/resources/application.yml` 读取 Redis、PostgreSQL 和 Pekko 参数，优先级为：JVM 系统属性 > 环境变量 > YAML > 内置默认值。环境变量名由启动代码明确映射，例如：

```powershell
$env:REDIS_HOST = 'redis.example.com'
$env:DB_URL = 'jdbc:postgresql://db.example.com:5432/gamedb'
$env:DB_USERNAME = 'game'
$env:DB_PASSWORD = '<database-password>'
$env:PEKKO_PORT = '4001'
```

## 本地运行

### 前置条件

- JDK 21；
- Docker Desktop 或可用的 Docker Compose；
- 使用仓库根目录的 `mvnw.cmd`，不要依赖全局 Maven；
- Protobuf 编译器由 Maven 插件解析，无需单独安装。

### 启动基础设施

在仓库根目录执行：

```powershell
docker compose up -d
docker compose ps
```

本地默认映射 Redis 6379 和 PostgreSQL 5432。停止容器但保留数据卷：

```powershell
docker compose down
```

### 构建和启动

```powershell
.\mvnw.cmd clean install
```

按以下顺序启动：

1. `game-node-login`；
2. `game-node-modules`；
3. `game-node-gateway`。

同机运行多个 Gateway 或 Logic 实例时，为每个实例分配不同的 Pekko Artery canonical 端口；对外 TCP/UDP/HTTP 端口也应通过配置或负载均衡避免冲突。

### Arthas GM 链路诊断

Logic 在启动时预加载 Arthas agent，并且只监听本机回环地址；不会对外暴露 Arthas Telnet 或 HTTP 端口。完整 Arthas 发行包在构建期解包，Arthas 自身日志会写入项目根目录 `.log/`，Logic 管理端口提供受令牌保护的 GM 诊断命令。

本地单独启动 Logic 前设置管理令牌，并确保 Redis、PostgreSQL 已准备好：

```powershell
$env:LOGIC_GM_TOKEN = 'replace-with-a-long-random-token'
.\deploy\local\start-logic.ps1 -Build
```

发送协议号后，命令会等待一条匹配的 Logic 消息，并返回 Arthas 的方法调用树与耗时。`timeoutMs` 的范围是 1000 到 30000：

```powershell
$headers = @{ 'X-GM-Token' = $env:LOGIC_GM_TOKEN }
$body = @{ protocolNumber = 1001; timeoutMs = 10000 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:9090/admin/gm/trace' -Headers $headers -ContentType 'application/json' -Body $body
```

返回值包含实际追踪的 Actor、handler、Arthas 命令和原始 trace 输出。客户端登录协议 `1000` 会在 Gateway 转换为 Logic 内部协议 `3003`，因此返回的有效协议号是 `3003`。管理令牌为空时，该端点不会注册。

## 测试客户端

原 `game-client` 模块已移除。登录压测使用 `xs-net/xs-net-sdk/target/code/unity/LoadTest/XsNet.LoadTest.csproj`，它直接复用统一生成的 Unity Runtime TCP/UDP 协议实现；先执行 SDK 生成，再启动服务端并运行该控制台工程完成联调与压测。
