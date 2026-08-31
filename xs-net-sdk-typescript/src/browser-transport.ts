import { AbstractTransport } from "./transport.js";

export class WebSocketTransport extends AbstractTransport {
  private socket?: WebSocket;

  public constructor(private readonly url: string) {
    super();
  }

  public connect(jwt: string): Promise<void> {
    this.close();
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(this.url);
      socket.binaryType = "arraybuffer";
      this.socket = socket;
      socket.onopen = () => {
        socket.send(new TextEncoder().encode(jwt));
        this.connectedState = true;
        resolve();
      };
      socket.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) this.emitMessage(new Uint8Array(event.data));
        else if (event.data instanceof Blob) void event.data.arrayBuffer().then((data) => this.emitMessage(new Uint8Array(data)));
      };
      socket.onerror = () => reject(new Error("WebSocket transport error"));
      socket.onclose = (event) => this.emitClose(event.reason ? new Error(event.reason) : undefined);
    });
  }

  public send(body: Uint8Array): void {
    if (!this.socket || !this.connected) throw new Error("WebSocket transport is not connected");
    this.socket.send(body);
  }

  public close(): void {
    this.connectedState = false;
    this.socket?.close();
    this.socket = undefined;
  }
}
