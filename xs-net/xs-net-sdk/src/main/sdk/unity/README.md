# XS Net Game SDK

由服务端消息协议自动生成的 Unity C# SDK，并内置**网络层**（TCP / 轻量可靠 UDP）。包含协议源文件（`proto/`）、C# 消息类型与消息路由表（`RpcRoute` 枚举），以及 `Runtime/Networking/` 下的手写网络客户端。

## 目录结构

```text
xs-net/xs-net-sdk/target/code/unity/
├── package.json            # UPM 包元数据（导入入口）
├── README.md
└── Runtime/                # C# 代码（命名空间 Game.Message）
    ├── Comment.cs          # 生成后出现
    ├── PlayerMessages.cs   # 生成后出现
    ├── RpcRoutes.cs        # 生成后出现
    └── Networking/         # 手写网络层（不会被重新导出清除）
        ├── Frame.cs              # XS packet：18 字节头（大端，含 headCRC/bodyCRC）
        ├── NetMessage.cs         # 消息信封（对应服务端 net_message.proto）
        ├── ReliableUdp.cs        # 轻量可靠 UDP 层（seq/累积ACK/重传/排序）
        ├── NetConnection.cs      # 连接抽象基类
        ├── TcpConnection.cs      # TCP 连接
        ├── UdpConnection.cs      # UDP 连接
        ├── MessageDispatcher.cs  # 按 RpcRoute 分发消息
        ├── NetworkConfig.cs      # 连接配置
        └── GameNetworkClient.cs  # 网络客户端门面
```

## 导入 Unity

1. `Window → Package Manager → + → Add package from disk...`
2. 运行 `.\scripts\Generate-Sdks.ps1 -Only Unity`
3. 选择 `xs-net/xs-net-sdk/target/code/unity/package.json`
4. 工程必须同时引入 `Google.Protobuf`（3.25.5）运行时，否则报 `The type or namespace name 'Google' could not be found`

## 消息路由表

所有 CS/SC 消息 ID 定义在生成的 `Runtime/RpcRoutes.cs` 中的 `RpcRoute` 枚举，由 `rpc_routes.proto` 生成。客户端协议号以它为准。

| 枚举名 | 值 | 方向 | 消息类型 | 说明 |
|---|---:|---|---|---|
| `RpcLogRecordCommand` | 999 | 内部 | `LogRecordCommand` | 日志记录命令 |
| `RpcCsLogin` | 1000 | C → S | `CS_Login` | 登录请求 |
| `RpcCsLogout` | 1001 | C → S | `CS_Logout` | 登出请求 |
| `RpcScLogin` | 1002 | S → C | `SC_Login` | 登录响应 |
| `RpcScLogout` | 1003 | S → C | `SC_Logout` | 登出响应 |
| `RpcCsLogReport` | 2100 | C → S | `CS_LogReport` | 日志上报请求 |
| `RpcScLogReport` | 2101 | S → C | `SC_LogReport` | 日志上报响应 |
| `RpcPlayerEnterCommand` | 3003 | 内部 | `PlayerEnterCommand` | 玩家进入命令 |

## 消息字段

### CS_Login (1000) — 登录请求

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| PlayerId | string | 1 |
| PlayerName | string | 2 |
| SessionId | string | 3 |

### SC_Login (1002) — 登录响应

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| Result | int32 | 1 |
| PlayerId | string | 2 |
| PlayerName | string | 3 |
| Token | string | 4 |

### CS_Logout (1001) — 登出请求

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| PlayerId | string | 1 |
| SessionId | string | 2 |

### SC_Logout (1003) — 登出响应

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| PlayerId | string | 1 |
| SessionId | string | 2 |

### CS_LogReport (2100) — 日志上报请求

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| SessionId | string | 1 |
| LogLevel | int32 | 2 |
| LogType | int32 | 3 |
| Content | string | 4 |

### SC_LogReport (2101) — 日志上报响应

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| SessionId | string | 1 |
| Result | int32 | 2 |
| LogId | int64 | 3 |

### LogRecordCommand (999) — 日志记录命令（内部）

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| Level | int32 | 1 |
| Type | int32 | 2 |
| Message | string | 3 |
| PlayerId | int64 | 4 |

### PlayerEnterCommand (3003) — 玩家进入命令（内部）

| 字段 | 类型 | proto 字段号 |
|---|---|---|
| PlayerId | int64 | 1 |
| PlayerName | string | 2 |
| ChannelId | string | 3 |

## 基本用法

### 发送请求（C → S）

```csharp
using Game.Message;
using Google.Protobuf;

var request = new CS_Login
{
    PlayerId = "player-1001",
    PlayerName = "Alice",
    SessionId = "session-abc"
};

int messageId = (int)RpcRoute.RpcCsLogin; // 1000
byte[] payload = request.ToByteArray();

SendToServer(messageId, payload);
```

### 接收响应（S → C）

```csharp
using Game.Message;

public static void Handle(int messageId, byte[] payload)
{
    switch ((RpcRoute)messageId)
    {
        case RpcRoute.RpcScLogin:
        {
            var response = SC_Login.Parser.ParseFrom(payload);
            // response.Result / response.PlayerId / response.PlayerName / response.Token
            break;
        }
        case RpcRoute.RpcScLogout:
        {
            var response = SC_Logout.Parser.ParseFrom(payload);
            break;
        }
        case RpcRoute.RpcScLogReport:
        {
            var response = SC_LogReport.Parser.ParseFrom(payload);
            break;
        }
    }
}
```

## 网络层使用（GameNetworkClient）

SDK 内置网络层（`Game.Message.Networking`），统一封装 TCP 与 UDP 连接、JWT 认证、帧协议编解码与消息分发。

```csharp
using Game.Message;
using Game.Message.Networking;

var config = new NetworkConfig
{
    Host = "127.0.0.1",
    TcpPort = 8082,      // 网关 TCP 接入
    UdpPort = 2002,      // 网关 UDP 接入（轻量可靠 UDP）
    UseUdp = false,      // true 走 UDP，false 走 TCP
};
var client = new GameNetworkClient(config);

client.OnConnected += () => Debug.Log("已连接");
client.OnDisconnected += () => Debug.Log("已断开");

// 注册消息处理器（强类型，自动反序列化）
client.Dispatcher.Register<RpcRoute>(RpcRoute.RpcScLogin, resp =>
{
    Debug.Log($"登录结果: {resp.Result}");
});
// 或注册原始 payload 处理器
client.Dispatcher.Register(RpcRoute.RpcScLogout, payload => { });

// 连接（jwt 为登录服签发的 token）
client.Connect("your-jwt-token");

// 发送请求
client.Send(RpcRoute.RpcCsLogin, new CS_Login
{
    PlayerId = "player-1001",
    PlayerName = "Alice",
    SessionId = "session-abc"
});

// 断开
client.Disconnect();
```

### 帧协议要点

- **TCP / UDP**：共用 `length(4) | packetId(2) | sendSeq(4) | random(4) | headCRC(2) | bodyCRC(2) | body(N)`（大端）格式。`packetId`：1=DATA、2=ACK、3=HELLO、4=HEARTBEAT、5=ERROR；`length` 是 body 长度，CRC 为 CRC-16/CCITT-FALSE。
- **握手与重连**：连接建立后发送 `HELLO(JWT)`，收到 `ACK` 即认证完成；自动重连时复用 `ConnectionRandom`，Unity `GameNetworkClient` 默认启用自动重连。
- **UDP 可靠性**：DATA 经可靠层保证有序、累计 ACK 与重传。
- 消息信封 `NetMessage` 字段：`rpc_num(1) / data(2) / error_code(3) / session_id(4) / user_id(5) / user_ip(6)`。

## 说明

- `Runtime/` 下 `Comment.cs`、`PlayerMessages.cs`、`RpcRoutes.cs` 与 `proto/*.proto` 均为自动生成，**不要手动修改**；服务端协议变更后重新执行仓库根目录的 `.\scripts\Generate-Sdks.ps1 -Only Unity` 即可刷新。
- `Runtime/Networking/` 为**手写网络层**，重新导出时不会被清除；如需修改请直接编辑该目录文件。
