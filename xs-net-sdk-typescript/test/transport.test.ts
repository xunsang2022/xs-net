import { createServer } from "node:net";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WebSocketTransport } from "../src/browser-transport.js";
import { GameClient } from "../src/client.js";
import { encodeTcpFrame, tryReadTcpFrame } from "../src/frame.js";
import { encodeNetMessage } from "../src/net-message.js";
import { TcpTransport } from "../src/node-transport.js";

describe("client transports", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends the JWT as the first TCP frame and dispatches a server message", async () => {
    let token = "";
    const server = createServer((socket) => {
      let buffered = new Uint8Array();
      socket.on("data", (chunk: Buffer) => {
        buffered = new Uint8Array([...buffered, ...chunk]);
        const frame = tryReadTcpFrame(buffered);
        if (!frame) return;
        buffered = frame.rest;
        token = new TextDecoder().decode(frame.body);
        socket.write(Buffer.from(encodeTcpFrame(encodeNetMessage(1002, new Uint8Array([7])))));
      });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const port = (server.address() as AddressInfo).port;
    const client = new GameClient(new TcpTransport({ host: "127.0.0.1", tcpPort: port }));
    const received = new Promise<Uint8Array>((resolve) => client.dispatcher.register(1002, resolve));

    await client.connect("jwt-token");

    await expect(received).resolves.toEqual(new Uint8Array([7]));
    expect(token).toBe("jwt-token");
    client.close();
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  });

  it("sends and receives binary data through the browser WebSocket adapter", async () => {
    const fake = new FakeWebSocket();
    vi.stubGlobal("WebSocket", class {
      public constructor(_url: string) {
        queueMicrotask(() => fake.onopen?.({} as Event));
        return fake;
      }
    });
    const transport = new WebSocketTransport("ws://example.test/game");
    const received = new Promise<Uint8Array>((resolve) => transport.onMessage(resolve));

    await transport.connect("jwt-token");
    fake.emit(new Uint8Array([9]).buffer);

    expect(new TextDecoder().decode(fake.sent[0] as Uint8Array)).toBe("jwt-token");
    await expect(received).resolves.toEqual(new Uint8Array([9]));
    transport.close();
  });
});

class FakeWebSocket {
  public binaryType: BinaryType = "blob";
  public onopen: ((event: Event) => void) | null = null;
  public onmessage: ((event: MessageEvent) => void) | null = null;
  public onerror: ((event: Event) => void) | null = null;
  public onclose: ((event: CloseEvent) => void) | null = null;
  public readonly sent: unknown[] = [];

  public send(data: unknown): void {
    this.sent.push(data);
  }

  public close(): void {
    this.onclose?.({ reason: "" } as CloseEvent);
  }

  public emit(data: ArrayBuffer): void {
    this.onmessage?.({ data } as MessageEvent);
  }
}
