# Game Server (游戏服务器)

基于 Spring Boot + Akka 的分布式游戏服务器架构。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 开发语言 |
| Spring Boot | 4.0.0 | Web 框架 |
| Akka | 2.8.5 | 分布式 Actor 模型 |
| SBE | 1.27.0 | 高性能二进制序列化 |
| JWT | 0.12.6 | 身份认证 |
| Lombok | 1.18.42 | 代码简化 |

## 项目结构

```
game-server/
├── game-comment/          # 评论模块
│   ├── config/           # 配置类
│   └── util/             # 工具类（JWT 等）
├── game-db/              # 数据库模块
│   ├── po/               # 持久化对象
│   │   ├── player/       # 玩家数据（背包、用户）
│   │   └── base/         # 基础 PO 类
│   └── repository/       # 数据访问层
├── game-message-action/  # 消息定义模块
│   ├── message/          # 消息协议定义
│   │   ├── action/       # 具体消息类型
│   │   └── actor/        # 消息处理 Actor
│   └── App.java
├── game-node-gateway/    # 网关节点
│   ├── gateway/          # 网关核心逻辑
│   │   ├── actor/        # 通道 Actor
│   │   ├── config/       # 网关配置
│   │   ├── controller/   # 控制器（背包、玩家）
│   │   ├── filter/       # 过滤器（JWT 认证）
│   │   └── model/        # 数据模型
│   └── GameGatewayApplication.java
├── game-node-login/      # 登录节点
│   ├── login/actor/      # 登录 Actor
│   └── LoginApplication.java
└── game-node-modules/    # 逻辑模块节点
    ├── logic/actor/      # 背包逻辑 Actor
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
  - 玩家登录认证
  - Token 生成与验证
  - 会话管理

### 3. game-node-modules（逻辑服务）
- **职责**:
  - 游戏核心逻辑处理
  - 背包系统
  - 道具使用

### 4. game-db（数据服务）
- **职责**:
  - 玩家数据持久化
  - 背包数据管理
  - 用户信息管理

### 5. game-message-action（消息协议）
- **职责**:
  - 定义消息协议（CS/SC）
  - 消息序列化/反序列化
  - Actor 消息处理

## 消息协议登记

| 消息类型 | 编号 | 说明 | 状态 |
|---------|------|------|------|
| CS_LOGIN | 1000 | 客户端登录请求 | 保留 |
| CS_Logout | 1001 | 登出请求 | 保留 |
| SC_LOGIN | 1002 | 服务端登录响应 DTO | 保留（不再作为 PlayerMessage） |
| SC_Logout | 1003 | 登出响应 | 保留 |
| CS_BACKPACK_INFO | 2000 | 背包信息请求 | 废弃，禁止复用 |
| SC_BACKPACK_INFO | 2001 | 背包信息响应 | 废弃，禁止复用 |
| CS_USE_ITEM | 2002 | 使用道具请求 | 废弃，禁止复用 |
| SC_USE_ITEM | 2003 | 使用道具响应 | 废弃，禁止复用 |
| BackpackInitMessage | 3000 | 背包初始化内部消息 | 废弃，禁止复用 |
| BackpackInitAckMessage | 3001 | 背包初始化确认内部消息 | 废弃，禁止复用 |
| BackpackCleanupMessage | 3002 | 背包清理内部消息 | 废弃，禁止复用 |
| DbSyncCmd 系列 | 3100-3199 | db-sync 内部消息区间 | 新增 |

`PlayerMessage` 已从 `com.game.message.actor.login` 迁移至 `com.game.message.actor.logic`，路由角色为 `logic`；`SC_LOGIN` 仅作为 HTTP 响应 DTO，不再实现 `PlayerMessage`。协议号 2000-2003、3000-3002 已废弃，禁止复用。

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

### 构建项目
```bash
mvn clean install
```

### 启动服务
建议按以下顺序启动：
1. game-node-login（登录服务）
2. game-node-modules（逻辑服务）
3. game-node-gateway（网关服务）

## 依赖关系

本项目依赖 `xs-net` 框架提供核心支持：
- `xs-net-all`: 核心框架打包
- `tool-all`: 工具集（SBE、ProtoBuf 等）

## 架构特点

- **分布式架构**: 基于 Akka Actor 模型的微服务架构
- **高性能**: 使用 SBE 二进制序列化协议
- **安全认证**: JWT Token 身份验证
- **模块化设计**: 清晰的模块划分，便于扩展和维护
