# xs-net

Java 21 多模块游戏服务端工程，包含底层 RPC/Actor 框架和游戏服务节点。

## 模块

项目根目录统一聚合以下两个 Maven 工程：

- `xs-net`：Pekko、RPC 消息注册、SDK 和构建插件等底层框架；
- `game-server`：Gateway、Login 和非 Spring Boot Logic 节点；

## 构建

在 Windows PowerShell 中于仓库根目录执行：

```powershell
.\mvnw.cmd -DskipTests package
```

包装脚本使用 JDK 21、IntelliJ IDEA 自带 Maven，并固定使用 `%USERPROFILE%\.m2\repository` 作为本地仓库。请不要依赖 PATH 中的全局 Maven。

## 本地运行

1. 启动 PostgreSQL 和 Redis：

```powershell
docker compose up -d
```

2. 构建工程后，按 Login → Logic → Gateway 的顺序启动服务。
3. 端口、配置项、启动细节和客户端联调方式见 [game-server/README.md](./game-server/README.md)。

## 配置与安全

仓库当前 YAML 保留了仅供本地开发的 Redis、PostgreSQL 和 JWT 默认值。生产环境必须通过部署配置覆盖它们；Gateway 与 Login 必须配置为完全一致、长度至少 32 字节的 JWT 密钥。

Logic 节点的配置优先级为 JVM 系统属性、环境变量、YAML、内置默认值。环境变量名由启动代码明确映射，例如：

```powershell
$env:DB_URL = 'jdbc:postgresql://db.example.com:5432/gamedb'
$env:DB_USERNAME = 'game'
$env:DB_PASSWORD = '<database-password>'
```

## 文档

- [ARCHITECTURE.md](./ARCHITECTURE.md)：跨模块架构、数据流和服务边界；
- [game-server/README.md](./game-server/README.md)：服务端构建、配置、端口与运行；
- [xs-net/README.md](./xs-net/README.md)：框架模块与 RPC 基础设施；
- [docs/sdk-generation.md](./docs/sdk-generation.md)：客户端 SDK 的统一生成流程与产物边界。

## 编码

源码、配置和文档统一使用 UTF-8。Windows PowerShell 5.1 读取文件时请显式指定编码：

```powershell
Get-Content -Encoding UTF8 .\game-server\README.md
```
