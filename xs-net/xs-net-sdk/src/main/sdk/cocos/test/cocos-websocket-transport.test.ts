import { describe, expect, it } from "vitest";
import { CocosWebSocketTransport } from "../src/cocos-websocket-transport";

describe("CocosWebSocketTransport", () => {
  it("sends the JWT and game payload as binary data", async () => {
    const socket = new FakeCocosWebSocket();
    const transport = new CocosWebSocketTransport("ws://example.test/game", () => socket);

    const received = new Promise<Uint8Array>((resolve) => transport.onMessage(resolve));
    const connecting = transport.connect("jwt-token");
    socket.open();
    await connecting;

    expect(socket.binaryType).toBe("arraybuffer");
    expect(new TextDecoder().decode(socket.sent[0])).toBe("jwt-token");
    transport.send(new Uint8Array([1, 2, 3]));
    expect(new Uint8Array(socket.sent[1])).toEqual(new Uint8Array([1, 2, 3]));

    socket.message(new Uint8Array([9, 8]).buffer);
    await expect(received).resolves.toEqual(new Uint8Array([9, 8]));
    transport.close();
  });
});

class FakeCocosWebSocket {
  public binaryType = "blob";
  public onopen: (() => void) | null = null;
  public onmessage: ((event: { data: ArrayBuffer | Uint8Array }) => void) | null = null;
  public onerror: (() => void) | null = null;
  public onclose: ((event: { reason?: string }) => void) | null = null;
  public readonly sent: ArrayBuffer[] = [];

  public send(data: ArrayBuffer): void {
    this.sent.push(data);
  }

  public close(): void {
    this.onclose?.({});
  }

  public open(): void {
    this.onopen?.();
  }

  public message(data: ArrayBuffer): void {
    this.onmessage?.({ data });
  }
}
