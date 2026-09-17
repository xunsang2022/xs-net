# XS-NET Cocos Creator SDK

这是面向 Cocos Creator 的 TypeScript SDK 模板。执行仓库根目录的生成脚本后，完整产物默认位于 `F:\project\xs-net\xs-net\xs-net-sdk\cocos`。

```powershell
.\scripts\Generate-Sdks.ps1 -Only Cocos
```

将生成目录中的 `src`、`generated` 和 `proto` 放入 Cocos Creator 工程即可使用：

```ts
import { CSLogin, CocosWebSocketTransport, GameClient, SCLogin } from "./sdk";

const client = new GameClient(
  new CocosWebSocketTransport("ws://127.0.0.1:8083/game"),
);
client.dispatcher.registerDecoded(1002, SCLogin.decode, (response) => {
  console.log(response.result);
});
await client.connect(token);
client.send(
  1000,
  CSLogin.encode(CSLogin.create({
    playerId: "1001",
    playerName: "Alice",
    sessionId: "s-1",
  })).finish(),
);
```

`CocosWebSocketTransport` 会以二进制 WebSocket 帧发送 JWT，并接收二进制协议帧；它不依赖 Node.js 的 `net` 或 `dgram` 模块。默认使用 Cocos 运行时提供的全局 `WebSocket`，也可以通过第二个参数注入测试或平台适配实现。

SDK 同时提供 `XsNetSdkTestPanel` 测试面板。将该组件挂到 Cocos 场景中的 Canvas 节点后运行，即可填写 WebSocket 地址和 JWT，执行连接、断开、发送 `CSLogin`，并查看连接状态和 `SCLogin` 响应日志。面板由脚本自动创建，不需要额外 Prefab。

```ts
import { XsNetSdkTestPanel } from "./sdk/src/sdk-test-panel";
```

协议文件和 `generated/` 下的代码均为生成产物，不要手工修改。协议变更后重新执行：

```powershell
.\scripts\Generate-Sdks.ps1 -Only Cocos
```
