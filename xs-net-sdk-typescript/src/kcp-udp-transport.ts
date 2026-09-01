import { createSocket, type Socket as UdpSocket } from "node:dgram";
import { AbstractTransport } from "./transport.js";
import { Kcp } from "./kcp.js";

export interface KcpUdpTransportOptions {
  host: string;
  kcpPort?: number;
  connectTimeoutMs?: number;
  retryIntervalMs?: number;
  heartbeatIntervalMs?: number;
  /** 拥塞/滑动窗口大小，同时用于发送与接收窗口。 */
  windowSize?: number;
  /** KCP 时钟刷新间隔（毫秒），也是 nodelay 的 interval。 */
  kcpIntervalMs?: number;
}

export class KcpUdpTransport extends AbstractTransport {
  private socket?: UdpSocket;
  private kcp?: Kcp;
  private clockTimer?: NodeJS.Timeout;
  private flushTimer?: NodeJS.Timeout;
  private connectTimer?: NodeJS.Timeout;
  private readonly options: Required<
    Pick<
      KcpUdpTransportOptions,
      "host" | "kcpPort" | "connectTimeoutMs" | "retryIntervalMs" | "heartbeatIntervalMs" | "windowSize" | "kcpIntervalMs"
    >
  >;

  public constructor(options: KcpUdpTransportOptions) {
    super();
    this.options = {
      host: options.host,
      kcpPort: options.kcpPort ?? 2003,
      connectTimeoutMs: options.connectTimeoutMs ?? 10000,
      retryIntervalMs: options.retryIntervalMs ?? 200,
      heartbeatIntervalMs: options.heartbeatIntervalMs ?? 10000,
      windowSize: options.windowSize ?? 32,
      kcpIntervalMs: options.kcpIntervalMs ?? 10
    };
  }

  public connect(jwt: string): Promise<void> {
    this.close();
    const socket = createSocket("udp4");
    this.socket = socket;
    const { host, kcpPort, connectTimeoutMs, kcpIntervalMs, windowSize, heartbeatIntervalMs } = this.options;

    let reject: ((error: Error) => void) | undefined;
    let settled = false;

    const kcp = new Kcp({
      conv: (Date.now() & 0xffffffff) >>> 0,
      sendDatagram: (data) => {
        // KCP 给出下一条 DATAGRAM，交由 UDP 发出。
        socket.send(Buffer.from(data), kcpPort, host);
      },
      onDeliver: (payload) => {
        // 认证约定：收到空载荷视为 auth-ok；非空载荷作为应用消息。
        if (!this.connected) {
          this.connectedState = true;
          settled = true;
          this.clearConnectTimer();
          if (reject) reject = undefined;
          if (payload.length === 0) {
            // 纯空载荷：仅用于完成认证握手。
            return;
          }
        }
        this.emitMessage(payload);
      }
    });
    this.kcp = kcp;
    // 标准可靠 UDP：开启 nodelay，快速重传并关闭自适应拥塞（充分使用滑动窗口）。
    kcp.setNodelay(1, kcpIntervalMs, 0, 1);
    kcp.setInterval(kcpIntervalMs);
    kcp.setWndsize(windowSize, windowSize);

    // 周期驱动 KCP 时钟（超时重传、窗口探测）。
    this.clockTimer = setInterval(() => kcp.update(Date.now()), kcpIntervalMs);

    socket.on("message", (message: Buffer) => {
      this.kcp?.input(new Uint8Array(message));
    });
    socket.on("error", (error) => {
      if (!settled) {
        settled = true;
        this.clearConnectTimer();
        reject?.(error);
      } else {
        this.emitClose(error);
      }
    });

    return new Promise<void>((resolve, rejectPromise) => {
      reject = rejectPromise;
      this.connectTimer = setTimeout(() => {
        if (settled) return;
        settled = true;
        socket.close();
        rejectPromise(new Error("KCP connect timeout"));
      }, connectTimeoutMs);

      socket.bind(() => {
        // 首条应用载荷即 JWT；send 后立即 update 触发 flush 发出去。
        kcp.send(new TextEncoder().encode(jwt));
        kcp.update(Date.now());
        // 周期 flush，保证空闲期也能及时把缓存中的 PUSH/ACK 发出去。
        this.flushTimer = setInterval(() => {
          if (this.connected) this.kcp?.update(Date.now());
        }, heartbeatIntervalMs);
      });
    });
  }

  public send(body: Uint8Array): void {
    if (!this.kcp || !this.connected) throw new Error("KCP transport is not connected");
    // 发完后立刻推进时钟以尽快落盘（flush）。
    this.kcp.send(body);
    this.kcp.update(Date.now());
  }

  public close(): void {
    this.connectedState = false;
    if (this.clockTimer) clearInterval(this.clockTimer);
    if (this.flushTimer) clearInterval(this.flushTimer);
    this.clearConnectTimer();
    this.clockTimer = undefined;
    this.flushTimer = undefined;
    this.socket?.close();
    this.socket = undefined;
    this.kcp = undefined;
  }

  private clearConnectTimer(): void {
    if (this.connectTimer) clearTimeout(this.connectTimer);
    this.connectTimer = undefined;
  }
}