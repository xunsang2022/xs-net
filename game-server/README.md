# Game Server (游戏服务器)

基于 Spring Boot + Akka 的分布式游戏服务器架构。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 开发语言 |
| Spring Boot | 4.0.0 | Web 框架 |
| Akka | 2.8.5 | 分布式 Actor 模型 |
| Protobuf | 3.25.5 | 消息序列化与协议定义 |
| Redis | - | logic 运行时存储、db-sync 中间层 |
| PostgreSQL | - | 最终落库目标 |
| SBE | 1.27.0 | 高性能二进制序列化 |
| JWT | 0.12.6 | 身份认证 |
| Lombok | 1.18.42 | 代码简化 |

## 项目结构

```
game-server/
├── game-comment/          # 公共模块：JWT、通用工具
├── game-db/              # db-sync 服务：Redis 脏数据回灌 PostgreSQL
│   ├── dbsync/           # DbSyncActor、批量落库、日志消费、回灌工具
│   ├── po/               # 持久化对象
│   └── DbSyncApplication.java
├── game-message-action/  # 消息定义模块
│   ├── message/          # 内部消息、路由接口与 RPC 注册表
│   ├── dto/              # HTTP DTO
│   ├── rpc/              # GameRpcEnvelope、GameRpcMessages
│   └── proto/            # protobuf 协议源文件
├── game-node-gateway/    # 网关节点
│   ├── gateway/          # 网关核心逻辑
│   │   ├── actor/        # 通道 Actor
│   │   ├── config/       # 网关配置
│   │   ├── controller/   # 玩家接口
│   │   ├── filter/       # 过滤器（JWT 认证）
│   └── GameGatewayApplication.java
├── game-node-login/      # 认证服务
│   ├── login/controller/ # 签发/校验 Token
│   └── LoginApplication.java
└── game-node-modules/    # 逻辑模块节点
    ├── logic/actor/      # PlayerActor、LogActor
    ├── logic/redis/      # RedisStore、DbSyncNotifier
    ├── logic/player/     # PlayerState
    └── GameLogicApplication.java
```

## 模块说明

### 1. game-node-gateway（网关服务）
- **端口**: 8080
- **职责**: 
  - 玩家连接管理
  - JWT 身份验证
  - 消息路由转发
  - 协议编解码

### 2. game-node-login（登录服务）
- **职责**:
  - 纯认证服务，只签发 JWT 与校验 JWT
  - 不创建玩家、不读写游戏数据、不启动 Akka Actor

### 3. game-node-modules（逻辑服务）
- **职责**:
  - 游戏核心逻辑处理
  - PlayerActor 运行时状态
  - Redis 脏数据写入与 db-sync 通知
  - 日志事件写入 Redis

### 4. game-db（数据服务）
- **职责**:
  - Redis 脏数据批量 UPSERT 到 PostgreSQL
  - 玩家创建/加载回灌
  - 日志事件消费与落库
  - 对账与回灌工具

### 5. game-message-action（消息协议）
- **职责**:
  - `.proto` 唯一协议源，protoc 生成 Java 类
  - RPC 编号注册表与 `GameRpcEnvelope` 路由

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
| DbSyncMessage | 3100 | db-sync 脏数据记账 | 新增 |
| DbSyncLoadMessage | 3101 | logic 请求加载/创建玩家 | 新增 |
| DbSyncLoadResultMessage | 3102 | db-sync 加载结果回执 | 新增 |

`PlayerMessage` 已迁移至 `com.game.message.actor.logic`，路由角色为 `logic`；`SC_LOGIN` 仅作为 HTTP 响应 DTO。协议号 2000-2003、3000-3002 已废弃，禁止复用。

## 配置说明

### Gateway 配置 (application.yml)
```yaml
server:
  port: 8080

jwt:
  secret: ${JWT_SECRET}
  expiration: 86400000  # 24 小时（毫秒）
```

### Akka 配置 (application.conf)
各个节点通过 Akka Remoting 进行分布式通信。

## 快速开始

### 前置要求
- JDK 21+
- Maven 3.6+
- protoc 3.25.5（默认读取 `C:/tools/protoc/bin/protoc.exe`，可通过 pom 的 `protocExecutable` 修改）

### 构建项目
```bash
mvn clean install
```

### 启动服务
建议按以下顺序启动：
1. game-db（db-sync 服务）
2. game-node-login（认证服务）
3. game-node-modules（logic 服务）
4. game-node-gateway（网关服务）

## 依赖关系

本项目依赖 `xs-net` 框架提供核心支持：
- `xs-net-all`: 核心框架打包
- `tool-all`: 工具集（SBE、ProtoBuf 等）

## 架构特点

- **分布式架构**: 基于 Akka Actor 模型的微服务架构
- **高性能**: 使用 SBE 二进制序列化协议
- **安全认证**: JWT Token 身份验证
- **模块化设计**: 清晰的模块划分，便于扩展和维护
