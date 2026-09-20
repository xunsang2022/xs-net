import { createConnection, type Socket } from "node:net";
import { createSocket, type Socket as UdpSocket } from "node:dgram";
import { AbstractTransport } from "./transport.js";
import { concatBytes, decodeUdpPacket, encodeUdpPacket, encodeTcpFrame, FrameType, randomUint32, tryReadTcpFrame } from "./frame.js";
import { ReliableUdp } from "./reliable-udp.js";

export interface NodeTransportOptions {
  host: string;
  tcpPort?: number;
  udpPort?: number;
  connectTimeoutMs?: number;
  retryIntervalMs?: number;
  heartbeatIntervalMs?: number;
  windowSize?: number;
}

export class TcpTransport extends AbstractTransport {
  private socket?: Socket;
  private readBuffer = new Uint8Array();
  private readonly connectionRandom = randomUint32();
  private sendSeq = 1;
  private readonly options: Required<Pick<NodeTransportOptions, "host" | "tcpPort" | "connectTimeoutMs">>;

  public constructor(options: NodeTransportOptions) {
    super();
    this.options = { host: options.host, tcpPort: options.tcpPort ?? 8082, connectTimeoutMs: options.connectTimeoutMs ?? 10000 };
  }

  public connect(jwt: string): Promise<void> {
    const previous = this.socket;
    this.socket = undefined;
    previous?.removeAllListeners();
    previous?.destroy();
    this.connectedState = false;
    this.readBuffer = new Uint8Array();
    return new Promise((resolve, reject) => {
      const socket = createConnection({ host: this.options.host, port: this.options.tcpPort });
      this.socket = socket;
      let settled = false;
      const timeout = setTimeout(() => {
        socket.destroy();
        reject(new Error("TCP connect timeout"));
      }, this.options.connectTimeoutMs);
      socket.once("connect", () => {
        clearTimeout(timeout);
        socket.write(Buffer.from(encodeTcpFrame(new TextEncoder().encode(jwt), FrameType.Hello, 0, this.connectionRandom)));
      });
      socket.on("data", (chunk: Buffer) => {
        this.readBuffer = new Uint8Array(concatBytes(this.readBuffer, new Uint8Array(chunk)));
        while (true) {
          const frame = tryReadTcpFrame(this.readBuffer);
          if (!frame) break;
          this.readBuffer = new Uint8Array(frame.rest);
          if (!this.connected && frame.packet.packetId === FrameType.Ack && frame.packet.random === this.connectionRandom) {
            this.connectedState = true;
            settled = true;
            resolve();
          } else if (this.connected && frame.packet.packetId === FrameType.Data) {
            this.emitMessage(frame.body);
          }
        }
      });
      socket.on("error", (error) => {
        if (!settled) reject(error);
        else this.emitClose(error);
      });
      socket.on("close", () => {
        if (settled) this.emitClose();
      });
    });
  }

  public send(body: Uint8Array): void {
    if (!this.socket || !this.connected) throw new Error("TCP transport is not connected");
    this.socket.write(Buffer.from(encodeTcpFrame(body, FrameType.Data, this.sendSeq++ >>> 0, this.connectionRandom)));
  }

  public close(): void {
    this.connectedState = false;
    this.socket?.destroy();
    this.socket = undefined;
    this.readBuffer = new Uint8Array();
  }
}

export class UdpTransport extends AbstractTransport {
  private socket?: UdpSocket;
  private reliable?: ReliableUdp;
  private retryTimer?: NodeJS.Timeout;
  private heartbeatTimer?: NodeJS.Timeout;
  private readonly options: Required<Pick<NodeTransportOptions, "host" | "udpPort" | "connectTimeoutMs" | "retryIntervalMs" | "heartbeatIntervalMs" | "windowSize">>;
  private readonly connectionRandom = randomUint32();

  public constructor(options: NodeTransportOptions) {
    super();
    this.options = {
      host: options.host,
      udpPort: options.udpPort ?? 2002,
      connectTimeoutMs: options.connectTimeoutMs ?? 10000,
      retryIntervalMs: options.retryIntervalMs ?? 200,
      heartbeatIntervalMs: options.heartbeatIntervalMs ?? 10000,
      windowSize: options.windowSize ?? 32
    };
  }

  public connect(jwt: string): Promise<void> {
    const previous = this.socket;
    this.socket = undefined;
    previous?.removeAllListeners();
    previous?.close();
    this.connectedState = false;
    if (this.retryTimer) clearInterval(this.retryTimer);
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    this.retryTimer = undefined;
    this.heartbeatTimer = undefined;
    this.reliable = undefined;
    return new Promise((resolve, reject) => {
      const socket = createSocket("udp4");
      this.socket = socket;
      let settled = false;
      const timeout = setTimeout(() => {
        socket.close();
        reject(new Error("UDP handshake timeout"));
      }, this.options.connectTimeoutMs);
      this.reliable = new ReliableUdp({
        windowSize: this.options.windowSize,
        random: this.connectionRandom,
        sendDatagram: (data) => socket.send(Buffer.from(data), this.options.udpPort, this.options.host),
        onDeliver: (body) => this.emitMessage(body)
      });
      socket.on("message", (message: Buffer) => {
        const packet = decodeUdpPacket(new Uint8Array(message));
        if (!packet || !this.reliable) return;
        if (!this.connected && packet.packetId === FrameType.Ack && packet.random === this.connectionRandom) {
          clearTimeout(timeout);
          this.connectedState = true;
          settled = true;
          resolve();
          return;
        }
        if (this.connected) this.reliable.receive(packet);
      });
      socket.on("error", (error) => {
        if (!settled) reject(error);
        else this.emitClose(error);
      });
      socket.bind(() => {
        socket.send(Buffer.from(encodeUdpPacket(
          FrameType.Hello, 0, this.connectionRandom, new TextEncoder().encode(jwt))), this.options.udpPort, this.options.host);
        this.retryTimer = setInterval(() => this.reliable?.retryOutstanding(), this.options.retryIntervalMs);
        this.heartbeatTimer = setInterval(() => {
          if (this.connected) socket.send(Buffer.from(encodeUdpPacket(
            FrameType.Heartbeat, this.reliable?.contiguousAck ?? 0, this.connectionRandom)));
        }, this.options.heartbeatIntervalMs);
      });
    });
  }

  public send(body: Uint8Array): void {
    if (!this.reliable || !this.connected) throw new Error("UDP transport is not connected");
    this.reliable.send(body);
  }

  public close(): void {
    this.connectedState = false;
    if (this.retryTimer) clearInterval(this.retryTimer);
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    this.retryTimer = undefined;
    this.heartbeatTimer = undefined;
    this.socket?.close();
    this.socket = undefined;
    this.reliable = undefined;
  }
}
