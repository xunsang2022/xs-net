# XS Net TypeScript SDK

This package provides the client runtime for Node.js and modern browsers. Run `npm run generate` after installing dependencies to copy every server `.proto` definition and generate `generated/` with `ts-proto`.

```ts
import { CSLogin, GameClient, SCLogin, TcpTransport } from "@xs-net/game-sdk-typescript";

const client = new GameClient(new TcpTransport({ host: "127.0.0.1" }));
client.dispatcher.registerDecoded(1002, SCLogin.decode, (response) => console.log(response.result));
await client.connect(token);
client.send(1000, CSLogin.encode(CSLogin.create({ playerId: "1001", playerName: "Alice", sessionId: "s-1" })).finish());
```

Node clients may use `TcpTransport` or `UdpTransport`. Browser clients must use `WebSocketTransport` and connect to the Gateway WebSocket endpoint, for example `ws://127.0.0.1:8083/game`.

`generated/` is build output and is intentionally ignored by Git. The protocol source is copied from `game-server/game-message-action/src/main/proto` and `xs-net/xs-net-message/src/main/proto`.
