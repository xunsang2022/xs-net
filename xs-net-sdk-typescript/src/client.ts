import { MessageDispatcher } from "./dispatcher.js";
import { decodeNetMessage, encodeNetMessage } from "./net-message.js";
import type { Transport } from "./transport.js";

export class GameClient {
  public readonly dispatcher = new MessageDispatcher();
  private readonly transportOff: () => void;

  public constructor(private readonly transport: Transport) {
    this.transportOff = transport.onMessage((body) => {
      const message = decodeNetMessage(body);
      this.dispatcher.dispatch(message.rpcNum, message.data);
    });
  }

  public connect(jwt: string): Promise<void> {
    return this.transport.connect(jwt);
  }

  public send(rpcNum: number, payload: Uint8Array): void {
    this.transport.send(encodeNetMessage(rpcNum, payload));
  }

  public close(): void {
    this.transportOff();
    this.transport.close();
  }

  public get connected(): boolean {
    return this.transport.connected;
  }
}
