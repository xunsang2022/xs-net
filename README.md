# xs-net

Java 21 多模块游戏服务端工程，包含工具库、网络框架和游戏服务节点。

## 构建

项目根目录现在统一聚合以下两个 Maven 工程：

- `xs-net`
- `game-server`

Windows PowerShell 下执行：

```powershell
./mvnw.ps1 -DskipTests package
```

脚本自动探测 IntelliJ IDEA 自带 Maven 与 JDK 21（可通过环境变量 `IDEA_MAVEN_HOME` / `JAVA_HOME` 显式指定），并固定使用 `%USERPROFILE%\.m2\repository` 作为本地仓库。也可以直接使用 `./mvnw.cmd`。

## 架构文档

完整架构、数据流转与技术栈见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

## 环境变量

运行游戏服务前请设置：

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/gamedb'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<your-database-password>'
$env:JWT_SECRET = '<at-least-32-byte-secret>'
```

配置文件不再保存数据库密码或 JWT 密钥。生产环境请通过部署平台的密钥管理功能注入这些变量。

## 编码

源码、配置和文档统一使用 UTF-8。Windows PowerShell 5.1 读取文件时请显式指定编码：

```powershell
Get-Content -Encoding UTF8 .\game-server\README.md
```
