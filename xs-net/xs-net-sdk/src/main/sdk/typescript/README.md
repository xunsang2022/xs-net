# XS Net TypeScript SDK

This package is the TypeScript runtime template used by the XS-NET SDK generator. The generated package is written to `xs-net/xs-net-sdk/target/code/typescript`; run the repository-level SDK generator before building or publishing it.

```ts
import { CSLogin, GameClient, SCLogin, TcpTransport } from "@xs-net/game-sdk-typescript";

const client = new GameClient(new TcpTransport({ host: "127.0.0.1" }));
client.dispatcher.registerDecoded(1002, SCLogin.decode, (response) => console.log(response.result));
await client.connect(token);
client.send(1000, CSLogin.encode(CSLogin.create({ playerId: "1001", playerName: "Alice", sessionId: "s-1" })).finish());
```

Node clients may use `TcpTransport` or `UdpTransport`. Browser clients must use `WebSocketTransport` and connect to the Gateway WebSocket endpoint, for example `ws://127.0.0.1:8083/game`.

The protocol source and `generated/` files are build output and are intentionally not stored in this template. They are produced from the server RPC model and `xs-net-message` by `scripts/Generate-Sdks.ps1`.
