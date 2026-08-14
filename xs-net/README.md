# XS-NET (游戏服务器框架)

基于 Akka + Netty + ProtoBuf 的高性能游戏服务器框架。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 开发语言 |
| Akka | 2.8.5 | 分布式 Actor 模型 |
| Netty | - | 网络通信框架 |
| SBE | 1.27.0 | 高性能二进制序列化 |
| Spring | 7.0.1 | 轻量级容器 |
| Lombok | 1.18.34 | 代码简化 |

## 项目结构

```
xs-net/
├── xs-net-core/          # 核心框架模块
│   ├── src/main/
│   │   ├── java/com/xs/core/
│   │   │   ├── actor/            # Actor 相关
│   │   │   │   ├── node/         # 节点 Actor
│   │   │   │   │   ├── Command.java
│   │   │   │   │   ├── NodeActor.java
│   │   │   │   │   └── Roles.java
│   │   │   │   ├── ActorRegisterManager.java  # Actor 注册管理
│   │   │   │   └── NodeConfig.java           # 节点配置
│   │   │   ├── akka/               # Akka 工具封装
│   │   │   │   └── AkkaKit.java
│   │   │   ├── config/             # 自动配置
│   │   │   │   ├── GameFrameworkAutoConfiguration.java
│   │   │   │   └── GameFrameworkProperties.java
│   │   │   └── spring/             # Spring 工具类
│   │   │       ├── ClassScanner.java
│   │   │       ├── ClassUtil.java
│   │   │       └── SpringUtil.java
│   │   └── resources/
│   │       └── application-framework-example.properties
│   └── pom.xml
├── xs-net-message/       # 消息协议模块
│   ├── src/main/
│   │   ├── java/com/xs/net/message/
│   │   │   ├── annotation/         # 注解定义
│   │   │   │   ├── ActionController.java
│   │   │   │   └── ActionMessage.java
│   │   │   ├── serializer/         # 序列化器
│   │   │   │   └── NetMessageSerializer.java
│   │   │   ├── INetMessage.java    # 消息接口
│   │   │   ├── MessageAble.java    # 消息能力接口
│   │   │   └── NetMessage.java     # 消息基类
│   │   └── resources/
│   │       └── application.conf    # Akka 配置
│   └── pom.xml
├── xs-net-tools/         # 工具模块
│   └── pom.xml
├── xs-net-all/           # 聚合打包模块
│   └── pom.xml
└── pom.xml
```

## 核心模块说明

### 1. xs-net-core（核心框架）
提供游戏服务器的核心功能：

- **Actor 系统**: 基于 Akka 的 Actor 模型实现
- **节点管理**: 支持多节点部署和通信
- **自动配置**: Spring Boot 风格的条件装配
- **类扫描**: 自动扫描和注册 Actor、Message

#### 核心组件

| 组件 | 说明 |
|------|------|
| NodeActor | 节点 Actor 基类 |
| ActorRegisterManager | Actor 注册管理器 |
| NodeConfig | 节点配置 |
| AkkaKit | Akka 工具封装 |
| GameFrameworkAutoConfiguration | 框架自动配置 |

### 2. xs-net-message（消息协议）
定义消息通信协议和序列化机制：

- **注解驱动**: 通过 `@ActionController` 和 `@ActionMessage` 定义消息处理器
- **序列化**: 支持多种序列化方式（SBE、ProtoBuf）
- **消息路由**: 基于注解的自动路由分发

#### 核心接口

| 接口/类 | 说明 |
|---------|------|
| INetMessage | 网络消息接口 |
| MessageAble | 消息能力接口 |
| NetMessage | 消息基类 |
| NetMessageSerializer | 消息序列化器 |

### 3. xs-net-tools（工具集）
集成各种工具库：

- SBE 序列化工具
- ProtoBuf 序列化工具
- LuBan 配置生成工具

### 4. xs-net-all（聚合包）
将所有模块打包，方便外部项目依赖使用。

## 配置示例

### application-framework-example.properties
```properties
# 启用 Actor 自动注册
game.framework.actor-auto-register-enabled=true

# 启用消息自动注册
game.framework.message-auto-register-enabled=true
```

### Akka 配置 (application.conf)
```hocon
akka {
  actor {
    provider = "remote"
  }
  remote {
    artery {
      enabled = on
    }
  }
}
```

## 快速开始

### 前置要求
- JDK 21+
- Maven 3.6+

### 构建项目
```bash
mvn clean install
```

### 使用方式

在您的游戏服务器项目中添加依赖：

```xml
<dependency>
    <groupId>com.xs</groupId>
    <artifactId>xs-net-all</artifactId>
    <version>0.0.1</version>
</dependency>
```

### 创建 Actor

```java
@ActionController
public class PlayerActor extends NodeActor {
    
    @ActionMessage
    public void handleLogin(CS_LOGIN login) {
        // 处理登录逻辑
    }
}
```

## 特性

### 1. 分布式支持
- 基于 Akka Remoting 实现节点间通信
- 支持 Aeron UDP 传输（可选）
- 高性能消息路由

### 2. 序列化协议
- **SBE**: Simple Binary Encoding，低延迟高吞吐
- **ProtoBuf**: Protocol Buffers，跨语言支持
- 可扩展的序列化器接口

### 3. 注解驱动
- `@ActionController`: 标记消息控制器
- `@ActionMessage`: 标记消息处理方法
- 自动扫描和注册

### 4. Spring 集成
- 自动配置支持
- Spring Bean 管理
- 条件装配

## 架构设计

```
┌─────────────────────────────────────────┐
│         Client (游戏客户端)              │
└───────────────┬─────────────────────────┘
                │ TCP/WebSocket
┌───────────────▼─────────────────────────┐
│   Gateway Node (网关节点)                │
│   - 连接管理                             │
│   - 协议编解码                           │
│   - 消息路由                             │
└───────────────┬─────────────────────────┘
                │ Akka Remoting
┌───────────────▼─────────────────────────┐
│   Logic Node (逻辑节点)                  │
│   - 业务逻辑处理                         │
│   - Actor 消息处理                       │
│   - 数据持久化                           │
└─────────────────────────────────────────┘
```

## 依赖关系

本项目为 `game-server` 提供底层框架支持：
- 分布式通信能力
- 消息协议定义
- 序列化工具
- Actor 模型基础设施

## 开发指南

### 1. 扩展序列化器
实现 `NetMessageSerializer` 接口自定义序列化方式。

### 2. 自定义 Actor
继承 `NodeActor` 类，使用 `@ActionController` 注解。

### 3. 定义消息
实现 `INetMessage` 接口或使用 `@ActionMessage` 注解。

## 注意事项

- 确保各个节点的 Akka 配置正确（特别是远程通信端口）
- 生产环境建议配置 JVM 参数优化性能
- 使用 SBE 时需要预先生成消息代码

## 许可证

MIT License
