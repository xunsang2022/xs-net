# Game Server (游戏服务器)

基于 Spring Boot + Akka + Netty 的分布式游戏服务器架构。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 开发语言 |
| Spring Boot | 4.0.0 | Gateway / Login Web 框架 |
| Akka | 2.8.5 | 分布式 Actor 模型（Cluster + Sharding，Artery/Aeron UDP） |
| Netty | 4.1.115 | Gateway TCP 长连接（客户端游戏消息 + S2C 下行） |
| Protobuf | 3.25.5 | 消息序列化与协议定义 |
| Redis | - | logic 运行时存储（Lettuce + commons-pool2 连接池） |
| PostgreSQL | - | 最终落库目标（HikariCP + Spring JDBC） |
| JWT | 0.12.6 | 身份认证（parser 缓存 + token 缓存） |
| Micrometer | 1.16.0 | logic 指标（flush 延迟 / dirty 深度 / 失败计数） |
| Lombok | 1.18.42 | 代码简化 |

## 项目结构

```
game-server/
├── game-comment/          # 公共模块：JWT、通用工具
├── game-message-action/  # 消息定义模块
│   ├── message/          # 内部消息、路由接口与 RPC 注册表
│   ├── dto/              # HTTP DTO
│   ├── rpc/              # GameRpcEnvelope、GameRpcMessages
│   └── proto/            # protobuf 协议源文件
├── game-node-gateway/    # 网关节点
│   ├── gateway/          # 网关核心逻辑
│   │   ├── actor/        # 通道 Actor（S2C 写回）
│   │   ├── config/       # 网关配置
│   │   ├── controller/   # 玩家接口
│   │   ├── filter/       # 过滤器（JWT 认证）
│   │   └── netty/        # NettyServer、ChannelRegistry、编解码
│   └── GameGatewayApplication.java
├── game-node-login/      # 认证服务
│   ├── login/controller/ # 签发/校验 Token
│   └── LoginApplication.java
└── game-node-modules/    # 逻辑模块节点（非 Spring Boot）
    ├── logic/actor/      # PlayerActor（cluster sharded）
    ├── logic/redis/      # RedisStore（Hash/Set/Streams）
    ├── logic/dbsync/     # DBSyncEngine、LogConsumer（进程内批写）
    ├── logic/enums/      # LogLevel、LogType
    ├── logic/player/     # PlayerState
    └── GameLogicApplication.java
```

## 模块说明

### 1. game-node-gateway（网关服务）
- **端口**: HTTP 8080 / Netty TCP 8082
- **职责**: 
  - Netty TCP 长连接（握手认证：首帧 JWT token 绑定 channelId）
  - JWT 身份验证（parser 缓存 + remote 模式 token 缓存）
  - 消息路由转发（上行经 Akka 到 Logic，下行 S2C 写回客户端）
  - 协议编解码（4 字节长度前缀 + protobuf NetMessage）

### 2. game-node-login（登录服务）
- **职责**:
  - 纯认证服务，只签发 JWT 与校验 JWT
  - 不创建玩家、不读写游戏数据、不启动 Akka Actor

### 3. game-node-modules（逻辑服务）
- **职责**:
  - 游戏核心逻辑处理
  - PlayerActor 运行时状态（stash 容量 512）
  - Redis 脏数据即时写 + DBSyncEngine 进程内批量 UPSERT（version 乐观锁）
  - 日志事件写 Redis Streams（protobuf），LogConsumer 阻塞消费落库
  - Micrometer 指标上报

## 消息协议登记

| 消息类型 | 编号 | 说明 | 状态 |
|---------|------|------|------|
| LogRecordMessage | 999 | 内部日志记录 | 新增 |
| CS_LOGIN | 1000 | 客户端登录请求 | 保留 |
| CS_Logout | 1001 | 登出请求 | 保留 |
| SC_LOGIN | 1002 | 服务端登录响应 DTO | 保留（不再作为 PlayerMessage） |
| SC_Logout | 1003 | 登出响应 | 保留 |
| CS_LOG_REPORT | 2100 | 客户端日志上报 | 新增 |
| SC_LOG_REPORT | 2101 | 日志上报响应 | 新增 |
| CS_BACKPACK_INFO | 2000 | 背包信息请求 | 废弃，禁止复用 |
| SC_BACKPACK_INFO | 2001 | 背包信息响应 | 废弃，禁止复用 |
| CS_USE_ITEM | 2002 | 使用道具请求 | 废弃，禁止复用 |
| SC_USE_ITEM | 2003 | 使用道具响应 | 废弃，禁止复用 |
| BackpackInitMessage | 3000 | 背包初始化内部消息 | 废弃，禁止复用 |
| BackpackInitAckMessage | 3001 | 背包初始化确认内部消息 | 废弃，禁止复用 |
| BackpackCleanupMessage | 3002 | 背包清理内部消息 | 废弃，禁止复用 |
| PlayerEnter | 3003 | 玩家进入 logic 内部消息 | 新增 |

`PlayerMessage` 已迁移至 `com.game.message.actor.logic`，路由角色为 `logic`；`SC_LOGIN` 仅作为 HTTP 响应 DTO。协议号 2000-2003、3000-3002 已废弃，禁止复用。DbSync 协议（3100-3102）已随独立 db-sync 服务移除。

## 配置说明

### Gateway 配置 (application.yml)
```yaml
server:
  port: 8080

jwt:
  secret: ${JWT_SECRET}
  expiration: 86400000  # 24 小时（毫秒）

game:
  netty:
    port: ${NETTY_PORT:8082}  # Netty TCP 长连接端口
```

### Akka 配置 (application.conf)
各个节点通过 Akka Remoting 进行分布式通信。`canonical.hostname` / `canonical.port` 可通过环境变量 `AKKA_HOSTNAME` / `AKKA_PORT` 覆盖。

## 快速开始

### 前置要求
- JDK 21+
- Maven 3.6+（或使用 `mvnw.ps1` 自动探测 IDEA 自带 Maven）
- protoc 由 Maven 插件自动下载（`protocArtifact`），无需本地安装
- **基础设施**：PostgreSQL 17+、Redis 7+（本机没装的话直接用 WSL Docker Compose，见下节）

### 启动基础设施（WSL Docker，推荐）

三个应用的 `application.yml` 明文默认值与仓库根目录 `docker-compose.yml` 完全一致，一条命令起：

```bash
# 在 WSL 中执行（Docker Desktop 已打开 WSL2 集成）
cd /mnt/e/project/xs-net
docker compose up -d
docker compose ps        # 期望两个容器 STATUS = Up (healthy)
docker compose exec postgres psql -U root -d gamedb -c "\\dt"   # 期望看到 users / log_events 两张表
```

Windows 侧（IDEA 应用）通过 `127.0.0.1:5432`、`127.0.0.1:6379` 直连；Docker Desktop 的 WSL2 集成自动做端口转发，无需额外配置。

```bash
# 停止（保留数据卷）
docker compose down
# 停止 + 清空全部数据
docker compose down -v
```

### 构建项目
```bash
mvn clean install
```

### 启动服务
建议按以下顺序启动（IDEA 右上角 Run Configuration 已预设 3 项）：
1. game-node-login（认证服务，端口 8081）
2. game-node-modules（logic 服务，Akka Cluster 端口 4001）
3. game-node-gateway（网关服务，HTTP 8080 + Netty TCP 8082，Akka Cluster 端口 2001）

日志输出到项目根目录 `.log/`：`login.log`、`logic.log`、`gateway.log`（7 天滚动）。

## 依赖关系

本项目依赖 `xs-net` 框架提供核心支持：
- `xs-net-all`: 核心框架打包
- `tool-all`: 工具集（ProtoBuf 等）

## 架构特点

- **分布式架构**: 基于 Akka Actor 模型的微服务架构
- **长连接通信**: Netty TCP 长连接承载游戏消息，S2C 下行实时推送
- **写合并落库**: DBSyncEngine 进程内批量 UPSERT，version 乐观锁防覆盖
- **安全认证**: JWT Token 身份验证
- **模块化设计**: 清晰的模块划分，便于扩展和维护