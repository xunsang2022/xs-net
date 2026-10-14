# XS-NET

XS-NET 是面向 Java 游戏服务端的框架与示例工作区，围绕 Apache Pekko Typed/Cluster、RPC 消息契约、Protobuf 和多平台客户端 SDK 构建。

[English](README_EN.md) · [框架说明](xs-net/README.md) · [游戏服务端说明](game-server/README.md) · [OpenWiki](openwiki/index.md) · [SDK 生成说明](openwiki/framework/xs-net-sdk/sdk-generation.md) · [架构说明](ARCHITECTURE.md) · [贡献指南](CONTRIBUTING.md) · [变更记录](CHANGELOG.md) · [安全报告](SECURITY.md) · [第三方许可证](THIRD_PARTY_NOTICES.md)

## 项目组成

仓库包含两个 Maven reactor：

| 目录 | 内容 | 说明文档 |
| --- | --- | --- |
| `xs-net/` | 可复用框架模块：Actor/集群封装、消息契约、传输抽象、RPC Maven 插件、SDK 模板与工具 | [框架 README](xs-net/README.md) |
| `game-server/` | 展示框架用法的游戏服务端示例：Gateway、Login、Logic 与协议定义 | [游戏服务端 README](game-server/README.md) |

Gateway 提供 Netty TCP/UDP/WebSocket 接入；Logic 使用 Pekko Cluster Sharding。当前示例还集成 Redis、PostgreSQL、JWT、指标和客户端 SDK 生成。

## 文档导航

| 文档 | 说明 |
| --- | --- |
| [框架 README](xs-net/README.md) | `xs-net/` 可复用框架：Actor/集群、消息契约、传输抽象、RPC 插件与 SDK 生成 |
| [游戏服务端 README](game-server/README.md) | `game-server/` 示例服务端：Gateway/Login/Logic 的模块划分、协议与构建启动 |
| [OpenWiki 索引](openwiki/index.md) | 工程 Wiki 根索引，含通用、框架、业务三部分 |

OpenWiki 分区入口：

- 通用：[快速上手](openwiki/general/quickstart.md) · [运行时架构](openwiki/general/architecture/overview.md) · [源码地图](openwiki/general/architecture/source-map.md) · [消息注册流程](openwiki/general/architecture/message-registration.md)
- 框架层：[消息框架概念](openwiki/framework/xs-net-message/concepts.md) · [SDK 生成说明](openwiki/framework/xs-net-sdk/sdk-generation.md)
- 业务层：[业务层总览](openwiki/business/game-server/index.md) · [运行时流程](openwiki/business/game-server/workflows/runtime-flows.md) · [工程与运维指南](openwiki/business/game-server/operations/engineering-guide.md) · [Gateway 传输说明](openwiki/business/game-node-gateway/transport.md)

## 环境要求

- JDK 21
- Maven 3.9.x（或仓库 Maven Wrapper）
- Docker Compose（仅运行本地 Redis/PostgreSQL 示例时需要）

## 构建

在仓库根目录构建全部模块：

```shell
bash ./mvnw clean verify
```

Windows PowerShell：

```powershell
.\mvnw.cmd clean verify
```

只构建框架及其依赖：

```shell
bash ./mvnw -pl xs-net -am clean verify
```

首次构建会下载 Maven 依赖。SDK 代码由 Maven 插件生成；生成说明见 [OpenWiki SDK 生成说明](openwiki/framework/xs-net-sdk/sdk-generation.md)。

## 本地运行示例

先启动 Redis 和 PostgreSQL：

```shell
docker compose up -d
```

再按 [游戏服务端说明](game-server/README.md) 构建并启动 Login、Logic、Gateway。示例配置仅供本地开发；部署前请替换 JWT 密钥、数据库凭据和管理令牌。

## 持续集成

Gitee Go 工作流位于 `.workflow/xsnet-ci.yml`，会在向 `master`/`main` 推送时将 `openwiki/` 同步到独立的 Gitee Wiki 仓库。Maven/JDK 验证在本地执行。

## 使用与参与

项目目前处于早期开发阶段，模块 API、协议和部署配置可能调整。使用前请查看 [架构说明](ARCHITECTURE.md)；问题、功能建议和代码贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

项目采用 [Apache-2.0 许可证](LICENSE)；KCP 算法移植另遵循上游 MIT 许可，见[第三方许可证说明](THIRD_PARTY_NOTICES.md)。
