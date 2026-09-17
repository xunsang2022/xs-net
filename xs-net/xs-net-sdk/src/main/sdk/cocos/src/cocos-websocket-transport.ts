import { AbstractTransport } from "./transport.js";

export interface CocosWebSocket {
  binaryType: string;
  onopen: (() => void) | null;
  onmessage: ((event: { data: ArrayBuffer | Uint8Array }) => void) | null;
  onerror: (() => void) | null;
  onclose: ((event: { reason?: string }) => void) | null;
  send(data: ArrayBuffer): void;
  close(): void;
}

export type CocosWebSocketFactory = (url: string) => CocosWebSocket;

function defaultWebSocketFactory(url: string): CocosWebSocket {
  if (!globalThis.WebSocket) {
    throw new Error("Cocos WebSocket is not available in the current runtime");
  }
  return new globalThis.WebSocket(url) as unknown as CocosWebSocket;
}

function encodeUtf8(value: string): Uint8Array {
  if (globalThis.TextEncoder) return new globalThis.TextEncoder().encode(value);

  const encoded = encodeURIComponent(value);
  const bytes: number[] = [];
  for (let index = 0; index < encoded.length;) {
    if (encoded[index] === "%") {
      bytes.push(Number.parseInt(encoded.slice(index + 1, index + 3), 16));
      index += 3;
    } else {
      bytes.push(encoded.charCodeAt(index));
      index += 1;
    }
  }
  return Uint8Array.from(bytes);
}

function toBytes(data: ArrayBuffer | Uint8Array): Uint8Array {
  return data instanceof Uint8Array ? data : new Uint8Array(data);
}

export class CocosWebSocketTransport extends AbstractTransport {
  private socket?: CocosWebSocket;

  public constructor(
    private readonly url: string,
    private readonly createWebSocket: CocosWebSocketFactory = defaultWebSocketFactory,
  ) {
    super();
  }

  public connect(jwt: string): Promise<void> {
    this.close();
    return new Promise((resolve, reject) => {
      let socket: CocosWebSocket;
      try {
        socket = this.createWebSocket(this.url);
      } catch (error) {
        reject(error);
        return;
      }

      this.socket = socket;
      socket.binaryType = "arraybuffer";
      socket.onopen = () => {
        socket.send(new Uint8Array(encodeUtf8(jwt)).buffer);
        this.connectedState = true;
        resolve();
      };
      socket.onmessage = (event) => this.emitMessage(toBytes(event.data));
      socket.onerror = () => reject(new Error("Cocos WebSocket transport error"));
      socket.onclose = (event) => this.emitClose(event.reason ? new Error(event.reason) : undefined);
    });
  }

  public send(body: Uint8Array): void {
    if (!this.socket || !this.connected) {
      throw new Error("Cocos WebSocket transport is not connected");
    }
    this.socket.send(new Uint8Array(body).buffer);
  }

  public close(): void {
    const socket = this.socket;
    this.socket = undefined;
    this.connectedState = false;
    if (!socket) return;

    socket.onopen = null;
    socket.onmessage = null;
    socket.onerror = null;
    socket.onclose = null;
    socket.close();
  }
}
