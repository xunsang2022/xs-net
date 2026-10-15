# XS Net .NET 压测端

该工程直接编译 `../Runtime`，与 Unity SDK 共用网络协议、TCP/UDP 连接、JWT 首帧认证和 Protobuf 消息定义。

## 构建

需要安装 .NET 8 SDK：

```powershell
dotnet build xs-net/xs-net-sdk/target/code/unity/LoadTest/XsNet.LoadTest.csproj
```

## TCP 登录压测

先启动 Gateway TCP 服务，再执行：

```powershell
$env:JWT_SECRET = "本地配置中的 JWT 密钥"
dotnet run --project xs-net/xs-net-sdk/target/code/unity/LoadTest/XsNet.LoadTest.csproj -- --host 127.0.0.1 --protocol tcp --port 8082 --count 1000 --concurrency 200 --secret $env:JWT_SECRET
```

## UDP 登录压测

```powershell
$env:JWT_SECRET = "本地配置中的 JWT 密钥"
dotnet run --project xs-net/xs-net-sdk/target/code/unity/LoadTest/XsNet.LoadTest.csproj -- --host 127.0.0.1 --protocol udp --port 2002 --count 1000 --concurrency 200 --secret $env:JWT_SECRET
```

可选参数：

- `--count`：总连接数，默认 `1000`。
- `--concurrency`：最大并发连接数，默认 `200`。
- `--player-start`：玩家 ID 起始值，默认 `10001`。
- `--timeout-ms`：连接和登录响应超时，默认 `10000`。
- `--token-lifetime-seconds`：JWT 有效期，默认 `3600`。

输出包括成功数、失败数、总耗时、登录吞吐、平均延迟、p50、p95、p99 和最大延迟。压测密钥通过命令行参数或环境变量传入，不应提交到仓库。

## 多进程分片压测

单个进程承载上万连接时，进程内的线程数与定时器数量本身会成为瓶颈（测出的是压测机的极限，而非服务端容量）。上万 CCU 建议按分片启动多个进程，总 QPS 按分片数均摊：

```powershell
# 4 分片 × 5000 = 20000 CCU，总 QPS 500（每分片 125）
$dll = "xs-net/xs-net-sdk/target/code/unity/LoadTest/bin/Release/net8.0/XsNet.LoadTest.dll"
1..4 | ForEach-Object {
  $i = $_ - 1
  Start-Process dotnet -ArgumentList @($dll,
    "--host","127.0.0.1","--protocol","kcp","--port","2003",
    "--count","5000","--concurrency","5000","--qps","125",
    "--player-start",(17000000 + $i * 5000),"--secret",$env:JWT_SECRET,
    "--timeout-ms","40000","--hold-seconds","60","--logout",
    "--dump-raw","$env:TEMP\xsnet-lt\shard$i.csv") -NoNewWindow -PassThru
}
```

`--dump-raw <path>` 会把每个连接的原始时延写成 `playerId,status,connect,login,e2e` 的 CSV。单进程输出的分位无法跨进程合并，需要合并出全局分位时用该文件汇总（成功数求和，分位对全部样本重算）。
