export interface Transport {
  readonly connected: boolean;
  connect(jwt: string): Promise<void>;
  send(body: Uint8Array): void;
  close(): void;
  onMessage(handler: (body: Uint8Array) => void): () => void;
  onClose(handler: (error?: Error) => void): () => void;
}

export interface ReconnectOptions {
  minDelayMs?: number;
  maxDelayMs?: number;
  maxAttempts?: number;
}

export abstract class AbstractTransport implements Transport {
  protected connectedState = false;
  private readonly messageHandlers = new Set<(body: Uint8Array) => void>();
  private readonly closeHandlers = new Set<(error?: Error) => void>();

  public get connected(): boolean {
    return this.connectedState;
  }

  public abstract connect(jwt: string): Promise<void>;
  public abstract send(body: Uint8Array): void;
  public abstract close(): void;

  public onMessage(handler: (body: Uint8Array) => void): () => void {
    this.messageHandlers.add(handler);
    return () => this.messageHandlers.delete(handler);
  }

  public onClose(handler: (error?: Error) => void): () => void {
    this.closeHandlers.add(handler);
    return () => this.closeHandlers.delete(handler);
  }

  protected emitMessage(body: Uint8Array): void {
    for (const handler of this.messageHandlers) handler(body);
  }

  protected emitClose(error?: Error): void {
    this.connectedState = false;
    for (const handler of this.closeHandlers) handler(error);
  }
}

/** Reconnects the same logical transport after an unexpected close. */
export class ReconnectingTransport implements Transport {
  private connectedState = false;
  private manuallyClosed = false;
  private jwt?: string;
  private timer?: ReturnType<typeof setTimeout>;
  private attempts = 0;
  private readonly minDelayMs: number;
  private readonly maxDelayMs: number;
  private readonly maxAttempts: number;
  private readonly messageHandlers = new Set<(body: Uint8Array) => void>();
  private readonly closeHandlers = new Set<(error?: Error) => void>();
  private readonly offMessage: () => void;
  private readonly offClose: () => void;

  public constructor(private readonly delegate: Transport, options: ReconnectOptions = {}) {
    this.minDelayMs = options.minDelayMs ?? 500;
    this.maxDelayMs = options.maxDelayMs ?? 10_000;
    this.maxAttempts = options.maxAttempts ?? Number.POSITIVE_INFINITY;
    this.offMessage = delegate.onMessage((body) => {
      for (const handler of this.messageHandlers) handler(body);
    });
    this.offClose = delegate.onClose((error) => this.handleClose(error));
  }

  public get connected(): boolean {
    return this.connectedState;
  }

  public async connect(jwt: string): Promise<void> {
    this.manuallyClosed = false;
    this.jwt = jwt;
    this.attempts = 0;
    this.clearTimer();
    await this.delegate.connect(jwt);
    this.connectedState = true;
  }

  public send(body: Uint8Array): void {
    this.delegate.send(body);
  }

  public close(): void {
    this.manuallyClosed = true;
    this.connectedState = false;
    this.jwt = undefined;
    this.clearTimer();
    this.delegate.close();
  }

  public onMessage(handler: (body: Uint8Array) => void): () => void {
    this.messageHandlers.add(handler);
    return () => this.messageHandlers.delete(handler);
  }

  public onClose(handler: (error?: Error) => void): () => void {
    this.closeHandlers.add(handler);
    return () => this.closeHandlers.delete(handler);
  }

  private handleClose(error?: Error): void {
    this.connectedState = false;
    for (const handler of this.closeHandlers) handler(error);
    if (!this.manuallyClosed && this.jwt !== undefined) this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.timer || this.attempts >= this.maxAttempts) return;
    const delay = Math.min(this.maxDelayMs, this.minDelayMs * 2 ** this.attempts);
    this.attempts += 1;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      const jwt = this.jwt;
      if (this.manuallyClosed || jwt === undefined) return;
      void this.delegate.connect(jwt)
        .then(() => {
          this.connectedState = true;
          this.attempts = 0;
        })
        .catch(() => this.scheduleReconnect());
    }, delay);
  }

  private clearTimer(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
  }

  public dispose(): void {
    this.close();
    this.offMessage();
    this.offClose();
  }
}
