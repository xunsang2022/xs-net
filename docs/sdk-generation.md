# SDK 统一生成流程

## 设计原则

XS-NET 参考 io-net 的生成方式，不把 Unity、TypeScript、Cocos Creator、C++ 维护成仓库根目录下的四个独立 SDK 工程。SDK 生成能力属于 `xs-net/xs-net-sdk` 模块：

```text
xs-net/xs-net-sdk/
├── src/main/java/          # Maven 生成插件配置
├── src/main/sdk/           # 客户端运行时模板和构建模板
└── target/code/            # 默认生成结果；也可输出到指定目录
    ├── unity/
    ├── typescript/
    ├── cocos/
    └── cpp/
```

`src/main/sdk` 只存手写运行时、测试和构建描述；协议文件、Protobuf 代码和 TypeScript/C++ 构建目录均由生成流程写入输出目录。默认输出目录为 `F:\project\xs-net\xs-net\xs-net-sdk`。

## 生成链路

```text
Java RPC / DTO / @ProtoField
        ↓
xs-net-rpc-maven-plugin
        ↓
xs-net/xs-net-sdk/target/generated-proto
        + xs-net-message framework proto
        ↓
      protoc
   ↙      ↓       ↘
F:/project/xs-net/xs-net/xs-net-sdk/unity  F:/project/xs-net/xs-net/xs-net-sdk/typescript  F:/project/xs-net/xs-net/xs-net-sdk/cocos  F:/project/xs-net/xs-net/xs-net-sdk/cpp
```

仓库根目录的唯一入口是：

```powershell
.\scripts\Generate-Sdks.ps1
```

脚本会生成业务 RPC Proto，合并 `xs-net-message` 的框架 Proto，然后复制运行时模板并生成 Unity、TypeScript、Cocos Creator 和 C++ 客户端代码。

只生成一种语言：

```powershell
.\scripts\Generate-Sdks.ps1 -Only Unity
.\scripts\Generate-Sdks.ps1 -Only TypeScript
.\scripts\Generate-Sdks.ps1 -Only Cocos
.\scripts\Generate-Sdks.ps1 -Only Cpp
```

如需将产物输出到指定目录，可使用 `-OutputRoot`：

```powershell
.\scripts\Generate-Sdks.ps1 -Only Cocos -OutputRoot 'C:\Users\XOS\Documents\sdk'
```

## 模板和产物边界

| 语言 | 手写模板 | 生成结果 |
|---|---|---|
| Unity | `xs-net/xs-net-sdk/src/main/sdk/unity/` | `F:\project\xs-net\xs-net\xs-net-sdk\unity\` |
| TypeScript | `xs-net/xs-net-sdk/src/main/sdk/typescript/` | `F:\project\xs-net\xs-net\xs-net-sdk\typescript\` |
| Cocos Creator | `xs-net/xs-net-sdk/src/main/sdk/cocos/` | `F:\project\xs-net\xs-net\xs-net-sdk\cocos\` |
| C++ | `xs-net/xs-net-sdk/src/main/sdk/cpp/` | `F:\project\xs-net\xs-net\xs-net-sdk\cpp\` |

生成后的目录可以直接交给对应客户端工程：

- Unity 导入 `F:\project\xs-net\xs-net\xs-net-sdk\unity\package.json`；
- TypeScript 在 `F:\project\xs-net\xs-net\xs-net-sdk\typescript` 中执行 `npm install` 后构建或发布；
- Cocos Creator 使用 `F:\project\xs-net\xs-net\xs-net-sdk\cocos` 中的 TypeScript、`generated` 和 `proto` 目录；该目标不包含 Node.js TCP/UDP 依赖；
- C++ 在 `F:\project\xs-net\xs-net\xs-net-sdk\cpp` 中执行 CMake 配置和构建。

Unity 的 `Runtime/Networking/NetMessage.cs` 是手写运行时，因此 Unity 生成阶段不输出 `net_message.proto` 的 C# 类型；TypeScript 和 C++ 会生成框架 Proto 类型。

## 与 io-net 的对应关系

io-net 的思路是：

```text
Java 注解类型和源码注释
        ↓
JProtobuf 分析与 Proto 元数据
        ↓
Action / Broadcast Document
        ↓
模板生成客户端业务 API
```

XS-NET 当前对应为：

```text
Java RPC / DTO
        ↓
RPC 源码扫描器
        ↓
规范 Proto 和路由元数据
        ↓
统一 SDK 生成器 + 语言模板
        ↓
target/code 下的客户端产物
```

两者共同点是：服务端模型是唯一协议来源，生成结果属于 `target`，客户端语言不是四个独立的服务端工程。当前差异是 io-net 还会生成高层 Action、广播、监听器和示例 API，而 XS-NET 目前主要生成 Protobuf 消息类型，并复用各语言的手写网络运行时。Cocos Creator 目标复用 TypeScript 的协议代码，并额外提供 `CocosWebSocketTransport`。

## 工具要求

默认使用用户 Maven 缓存中的 Protobuf 3.25.5 `protoc`。也可以指定：

```powershell
.\scripts\Generate-Sdks.ps1 -Protoc 'D:\tools\protoc.exe'
```

TypeScript 生成器需要先在模板目录安装依赖：

```powershell
npm install --prefix xs-net/xs-net-sdk/src/main/sdk/typescript
.\scripts\Generate-Sdks.ps1 -Only TypeScript
```

生成完成后，如需构建生成的 TypeScript 包：

```powershell
Set-Location xs-net/xs-net-sdk/target/code/typescript
npm install
npm run typecheck
npm test
```

## 协议变更后的验证

```powershell
.\scripts\Generate-Sdks.ps1
```

然后按客户端语言验证：

```powershell
Set-Location xs-net/xs-net-sdk/target/code/typescript
npm run typecheck
npm run build
npm test
```

Unity 运行时和 C++ SDK 分别使用生成目录中的测试/构建工程。所有生成日志写入仓库根目录 `.log/`，不要手工修改 `target/code` 中的生成文件，也不要提交该目录。
