---
type: Integration Guide
title: SDK 统一生成流程
description: 从 Java RPC 契约生成 Unity、TypeScript、Cocos Creator 与 C++ 客户端 SDK 的流程、边界和验证方式。
resource: scripts/Generate-Sdks.ps1
tags: [xs-net, sdk, code-generation]
---
# SDK 统一生成流程

## 设计原则

XS-NET 不把 Unity、TypeScript、Cocos Creator、C++ 维护成仓库根目录下的四个独立 SDK 工程，SDK 生成能力统一属于 `xs-net/xs-net-sdk` 模块：

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

`src/main/sdk` 只存手写运行时、测试和构建描述；协议文件、Protobuf 代码和 TypeScript/C++ 构建目录均由生成流程写入输出目录。默认输出目录为 `xs-net/xs-net-sdk/target/code`，也可通过 `-OutputRoot` 输出到指定目录。

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
xs-net/xs-net-sdk/target/code/unity  xs-net/xs-net-sdk/target/code/typescript  xs-net/xs-net-sdk/target/code/cocos  xs-net/xs-net-sdk/target/code/cpp
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
| Unity | `xs-net/xs-net-sdk/src/main/sdk/unity/` | `xs-net/xs-net-sdk/target/code/unity/` |
| TypeScript | `xs-net/xs-net-sdk/src/main/sdk/typescript/` | `xs-net/xs-net-sdk/target/code/typescript/` |
| Cocos Creator | `xs-net/xs-net-sdk/src/main/sdk/cocos/` | `xs-net/xs-net-sdk/target/code/cocos/` |
| C++ | `xs-net/xs-net-sdk/src/main/sdk/cpp/` | `xs-net/xs-net-sdk/target/code/cpp/` |

生成后的目录可以直接交给对应客户端工程：

- Unity 导入 `xs-net/xs-net-sdk/target/code/unity/package.json`；
- TypeScript 在 `xs-net/xs-net-sdk/target/code/typescript` 中执行 `npm install` 后构建或发布；
- Cocos Creator 使用 `xs-net/xs-net-sdk/target/code/cocos` 中的 TypeScript、`generated` 和 `proto` 目录；该目标不包含 Node.js TCP/UDP 依赖；
- C++ 在 `xs-net/xs-net-sdk/target/code/cpp` 中执行 CMake 配置和构建。

Unity 的 `Runtime/Networking/NetMessage.cs` 是手写运行时，因此 Unity 生成阶段不输出 `net_message.proto` 的 C# 类型；TypeScript 和 C++ 会生成框架 Proto 类型。

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
