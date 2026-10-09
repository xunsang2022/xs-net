---
type: Documentation Index
title: "OpenWiki"
description: "xs-net 仓库工程 Wiki 的文件与子目录索引，按项目模块文件夹分类。"
---

# 目录结构

```text
openwiki/
├── index.md                              # 根索引
├── general/                              # 跨模块通用文档
│   ├── quickstart.md                     # 工程快速上手
│   └── architecture/                     # 通用架构
│       ├── overview.md                   # 运行时架构
│       ├── source-map.md                 # 模块与源码地图
│       └── message-registration.md       # 消息注册流程
├── framework/                            # 框架层 (xs-net)
│   ├── xs-net-message/                   # 消息框架核心
│   │   └── concepts.md                   # 领域概念
│   └── xs-net-sdk/                       # SDK 生成
│       └── sdk-generation.md             # 统一生成流程
└── business/                             # 业务层 (game-server)
    ├── game-server/                      # 业务层总览
    │   ├── extensions.md                 # 集成与扩展
    │   ├── operations/                   # 运维
    │   │   └── engineering-guide.md      # 工程与运维指南
    │   └── workflows/                    # 流程
    │       └── runtime-flows.md          # 运行时流程
    └── game-node-gateway/                # Gateway 模块
        └── transport.md                  # 四协议传输说明
```

# 快速上手

- [xs-net 工程快速上手](general/quickstart.md) - 面向工程师的入口：仓库定位、构建与测试命令、心智模型、按变更区域索引，以及当前必须遵守的约束。

# 通用文档

- [general](general/) - 跨模块的通用架构文档（仓库总览、源码地图、消息注册流程）。

# 框架层（xs-net）

- [framework/xs-net-message](framework/xs-net-message/) - 消息框架核心：RPC 契约、领域概念、注册表和请求上下文。
- [framework/xs-net-sdk](framework/xs-net-sdk/) - 统一多语言客户端 SDK 生成流程。

# 业务层（game-server）

- [business/game-server](business/game-server/) - 业务层总览：集成扩展、工程运维与运行时流程。
- [business/game-node-gateway](business/game-node-gateway/) - Gateway 模块：四协议接入（TCP/UDP/KCP/WebSocket）、认证与统一路由。
