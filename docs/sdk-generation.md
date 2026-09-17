# SDK 统一生成流程

## 目标

XS-NET 的 SDK 生成采用“一份协议模型，多种客户端产物”的方式。协议由服务端 Java RPC/DTO 定义产生，随后由统一协议集生成 Unity、TypeScript 和 C++ SDK。Unity 的 `NetMessage` 由 `Runtime/Networking/NetMessage.cs` 手写实现，因此 Unity 生成阶段会有意排除 `net_message.proto`，避免产生冲突命名空间；TypeScript/C++ 仍会生成该框架 Proto。

整体流程如下：

```text
Java RPC / DTO / @ProtoField + xs-net-message framework proto
        ↓
xs-net-rpc-maven-plugin
        ↓
xs-net/xs-net-sdk/target/generated-proto
        ↓
      protoc
   ↙      ↓       ↘
Unity  TypeScript  C++
```

这个流程参考了 io-net 的分层方式：先从服务端业务模型提取协议和元数据，再向不同客户端语言生成代码。当前实现保留了 XS-NET 自己的 RPC 编号、`NetMessage` 信封和 TCP/UDP/KCP 传输协议；生成器负责协议代码，网络运行时仍由 SDK 中的手写层负责。

## 唯一入口

在仓库根目录执行：

```powershell
.\scripts\Generate-Sdks.ps1
```

脚本会依次完成：

1. 使用 Maven 生成业务 RPC Proto，并合并 `xs-net-message` 框架 Proto；
2. 清理三个 SDK 中对应的旧生成文件；
3. 将规范 Proto 同步到各 SDK 的 `proto/`；
4. 使用同一个 `protoc` 版本生成 C#、TypeScript 和 C++ 代码；
5. 将运行日志写入根目录 `.log/`。

也可以只生成一种 SDK：

```powershell
.\scripts\Generate-Sdks.ps1 -Only Unity
.\scripts\Generate-Sdks.ps1 -Only TypeScript
.\scripts\Generate-Sdks.ps1 -Only Cpp
```

如果 `protoc` 不在默认 Maven 缓存路径，可以显式指定：

```powershell
.\scripts\Generate-Sdks.ps1 -Protoc 'D:\tools\protoc.exe'
```

TypeScript 生成需要先安装 SDK 的 Node 依赖：

```powershell
Set-Location xs-net-sdk-typescript
npm install
Set-Location ..
.\scripts\Generate-Sdks.ps1 -Only TypeScript
```

也可以通过 `-TsProtoPlugin` 指定 `protoc-gen-ts_proto` 的完整路径。

## 生成目录与手写代码边界

| SDK | 协议源 | 生成代码 | 手写代码 |
|---|---|---|---|
| Unity | `proto/`（不含 `net_message.proto`） | `Runtime/*.cs` | `Runtime/Networking/`，包括 `NetMessage.cs` |
| TypeScript | `proto/` | `generated/*.ts` | `src/` |
| C++ | `proto/` | `generated/*.h`、`generated/*.cc` | `include/`、`src/` |

生成目录不应手工修改。Unity 导出只清理 `Runtime` 根目录下的 `.cs`，不会删除 `Runtime/Networking/`，因此网络层可以继续独立演进。

## 与 io-net 生成方式的关系

io-net 的思路可以抽象为：

```text
Java 注解类型和源码注释
        ↓
JProtobuf 分析与 Proto 元数据
        ↓
Action / Broadcast Document
        ↓
模板生成 TypeScript / C# / GDScript 业务 API
```

XS-NET 当前对应关系是：

```text
Java RPC / DTO / ProtoField
        ↓
RPC 源码扫描器
        ↓
规范 Proto 和 RPC 路由
        ↓
protoc 生成跨语言消息类型
        ↓
各 SDK 手写或半手写的传输与分发层
```

两者相同的地方是：服务端模型是协议来源，客户端代码不应分别维护一套协议定义。不同的地方是：

- io-net 更偏向生成高层 Action API、广播 API、监听器和示例；
- XS-NET 当前更偏向生成底层 Protobuf 消息类型，并把传输层封装放在 SDK Runtime 中；
- io-net 可在框架层切换 Protobuf、JSON 等协议；XS-NET 当前 wire protocol 固定为 Protobuf 信封；
- XS-NET 的客户端最终需要遵守 `4 字节大端长度前缀 + NetMessage protobuf bytes`，不能只因为消息类相同就直接复用 io-net 客户端。

后续如果需要进一步靠近 io-net，可以在当前规范 Proto 和 RPC 路由元数据之上增加高层 Typed Client 生成，但不应让各语言重新维护 RPC 编号或消息字段。

## 旧入口说明

- `xs-net-sdk-cpp/scripts/Generate-Sdk.ps1` 现在只是统一入口的 C++ 包装，等价于 `Generate-Sdks.ps1 -Only Cpp`；
- `xs-net-sdk-typescript/scripts/generate-proto.mjs` 和 `sync-proto.mjs` 仍可用于 TypeScript 包的本地开发，但完整 SDK 发布建议使用仓库根目录入口；
- `game-message-action` 只负责生成服务端使用的 Proto 和 Java 代码，不再在 Maven 生命周期中自动导出 Unity SDK，避免一次构建产生两套 SDK 结果。

## 变更协议后的检查

修改 RPC 或 DTO 后，应执行：

```powershell
.\scripts\Generate-Sdks.ps1
```

然后分别验证：

```powershell
Set-Location xs-net-sdk-typescript
npm run typecheck
npm test
Set-Location ..

dotnet test xs-net-sdk-unity/Tests~/SdkTests.csproj
```

C++ SDK 需要先安装 Protobuf 和 Standalone Asio，再执行对应 CMake 构建和测试。生成失败时优先查看 `.log/sdk-*.log` 与 `.log/sdk-*.err.log`，不要直接修改生成目录中的代码。
