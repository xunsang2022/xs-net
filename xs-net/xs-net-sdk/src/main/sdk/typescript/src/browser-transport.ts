import { AbstractTransport } from "./transport.js";
import { encodeTcpFrame, FrameType, randomUint32, tryReadTcpFrame } from "./frame.js";

export class WebSocketTransport extends AbstractTransport {
  private socket?: WebSocket;
  private readonly connectionRandom = randomUint32();
  private readBuffer = new Uint8Array();
  private sendSeq = 1;

  public constructor(private readonly url: string) {
    super();
  }

  public connect(jwt: string): Promise<void> {
    const previous = this.socket;
    this.socket = undefined;
    if (previous) {
      previous.onopen = null;
      previous.onmessage = null;
      previous.onerror = null;
      previous.onclose = null;
      previous.close();
    }
    this.connectedState = false;
    this.readBuffer = new Uint8Array();
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(this.url);
      socket.binaryType = "arraybuffer";
      this.socket = socket;
      socket.onopen = () => {
        socket.send(encodeTcpFrame(new TextEncoder().encode(jwt), FrameType.Hello, 0, this.connectionRandom));
      };
      socket.onmessage = async (event) => {
        let bytes: Uint8Array | undefined;
        if (event.data instanceof ArrayBuffer) {
          bytes = new Uint8Array(event.data);
        } else if (typeof Blob !== "undefined" && event.data instanceof Blob) {
          bytes = new Uint8Array(await event.data.arrayBuffer());
        }
        if (!bytes) return;
        this.readBuffer = new Uint8Array([...this.readBuffer, ...bytes]);
        while (true) {
          const frame = tryReadTcpFrame(this.readBuffer);
          if (!frame) break;
          this.readBuffer = frame.rest;
          if (!this.connected && frame.packet.packetId === FrameType.Ack && frame.packet.random === this.connectionRandom) {
            this.connectedState = true;
            resolve();
          } else if (this.connected && frame.packet.packetId === FrameType.Data) {
            this.emitMessage(frame.body);
          }
        }
      };
      socket.onerror = () => reject(new Error("WebSocket transport error"));
      socket.onclose = (event) => this.emitClose(event.reason ? new Error(event.reason) : undefined);
    });
  }

  public send(body: Uint8Array): void {
    if (!this.socket || !this.connected) throw new Error("WebSocket transport is not connected");
    this.socket.send(encodeTcpFrame(body, FrameType.Data, this.sendSeq++ >>> 0, this.connectionRandom));
  }

  public close(): void {
    this.connectedState = false;
    this.socket?.close();
    this.socket = undefined;
    this.readBuffer = new Uint8Array();
  }
}
