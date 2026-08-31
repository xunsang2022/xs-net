export type RawMessageHandler = (payload: Uint8Array, route: number) => void;

export class MessageDispatcher {
  private readonly handlers = new Map<number, RawMessageHandler[]>();

  public register(route: number, handler: RawMessageHandler): () => void {
    const handlers = this.handlers.get(route) ?? [];
    handlers.push(handler);
    this.handlers.set(route, handlers);
    return () => {
      const current = this.handlers.get(route);
      if (!current) return;
      const index = current.indexOf(handler);
      if (index >= 0) current.splice(index, 1);
      if (current.length === 0) this.handlers.delete(route);
    };
  }

  public registerDecoded<T>(route: number, decode: (payload: Uint8Array) => T, handler: (message: T) => void): () => void {
    return this.register(route, (payload) => handler(decode(payload)));
  }

  public dispatch(route: number, payload: Uint8Array): void {
    for (const handler of this.handlers.get(route) ?? []) handler(payload, route);
  }
}
