export interface Transport {
  readonly connected: boolean;
  connect(jwt: string): Promise<void>;
  send(body: Uint8Array): void;
  close(): void;
  onMessage(handler: (body: Uint8Array) => void): () => void;
  onClose(handler: (error?: Error) => void): () => void;
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
