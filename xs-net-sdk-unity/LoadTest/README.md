# XS Net .NET 压测端

该工程直接编译 `../Runtime`，与 Unity SDK 共用网络协议、TCP/UDP 连接、JWT 首帧认证和 Protobuf 消息定义。

## 构建

需要安装 .NET 8 SDK：

```powershell
dotnet build xs-net-sdk-unity/LoadTest/XsNet.LoadTest.csproj
```

## TCP 登录压测

先启动 Gateway TCP 服务，再执行：

```powershell
$env:JWT_SECRET = "本地配置中的 JWT 密钥"
dotnet run --project xs-net-sdk-unity/LoadTest/XsNet.LoadTest.csproj -- --host 127.0.0.1 --protocol tcp --port 8082 --count 1000 --concurrency 200 --secret $env:JWT_SECRET
```

## UDP 登录压测

```powershell
$env:JWT_SECRET = "本地配置中的 JWT 密钥"
dotnet run --project xs-net-sdk-unity/LoadTest/XsNet.LoadTest.csproj -- --host 127.0.0.1 --protocol udp --port 2002 --count 1000 --concurrency 200 --secret $env:JWT_SECRET
```

可选参数：

- `--count`：总连接数，默认 `1000`。
- `--concurrency`：最大并发连接数，默认 `200`。
- `--player-start`：玩家 ID 起始值，默认 `10001`。
- `--timeout-ms`：连接和登录响应超时，默认 `10000`。
- `--token-lifetime-seconds`：JWT 有效期，默认 `3600`。

输出包括成功数、失败数、总耗时、登录吞吐、平均延迟、p50、p95、p99 和最大延迟。压测密钥通过命令行参数或环境变量传入，不应提交到仓库。
