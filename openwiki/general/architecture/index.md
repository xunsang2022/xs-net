---
type: Documentation Index
title: "通用架构文档"
description: "跨模块的通用架构文档，包含仓库总览、源码地图和跨模块流程。"
---

# Files

- [运行时架构](overview.md) - xs-net 的分层与依赖方向、Gateway/Login/Logic/Battle 运行角色、控制面与数据面、线程模型，以及集群与分片策略。
- [模块与源码地图](source-map.md) - 两个 reactor 各模块的职责、关键入口、生成源、测试分布与跨模块修改热区。
- [消息注册流程](message-registration.md) - RPC 消息从定义到运行时注册表的完整流程，包含编译时生成、启动时初始化、路由注册表构建。
