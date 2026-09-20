import { encodeUdpPacket, FrameType, type UdpPacket } from "./frame.js";

export interface ReliableUdpOptions {
  windowSize?: number;
  sendDatagram: (data: Uint8Array) => void;
  onDeliver: (payload: Uint8Array) => void;
  random?: number;
}

export class ReliableUdp {
  private readonly windowSize: number;
  private readonly sendDatagram: (data: Uint8Array) => void;
  private readonly onDeliver: (payload: Uint8Array) => void;
  private readonly random: number;
  private readonly inFlight = new Map<number, Uint8Array>();
  private readonly reorder = new Map<number, Uint8Array>();
  private sendSeq = 1;
  private nextReceiveSeq = 1;
  private highestAck = 0;

  public constructor(options: ReliableUdpOptions) {
    this.windowSize = Math.max(1, options.windowSize ?? 32);
    this.sendDatagram = options.sendDatagram;
    this.onDeliver = options.onDeliver;
    this.random = options.random ?? 0;
  }

  public send(payload: Uint8Array): void {
    if (this.inFlight.size >= this.windowSize) {
      const oldest = this.inFlight.keys().next().value;
      if (oldest !== undefined) this.inFlight.delete(oldest);
    }
    const sequence = this.sendSeq++ >>> 0;
    this.inFlight.set(sequence, payload);
    this.emit(FrameType.Data, sequence, payload);
  }

  public receive(packet: UdpPacket): void {
    if (packet.packetId === FrameType.Ack && packet.sendSeq > 0) {
      for (const sequence of this.inFlight.keys()) {
        if (sequence <= packet.sendSeq) this.inFlight.delete(sequence);
      }
    }
    if (packet.packetId !== FrameType.Data) return;
    if (packet.sendSeq < this.nextReceiveSeq) {
      this.emit(FrameType.Ack, 0);
      return;
    }
    if (packet.sendSeq === this.nextReceiveSeq) {
      this.deliver(packet.sendSeq, packet.body);
      while (this.reorder.has(this.nextReceiveSeq)) {
        const payload = this.reorder.get(this.nextReceiveSeq);
        this.reorder.delete(this.nextReceiveSeq);
        if (payload) this.deliver(this.nextReceiveSeq, payload);
      }
    } else if (this.reorder.size < this.windowSize * 2) {
      this.reorder.set(packet.sendSeq, packet.body);
    }
    this.emit(FrameType.Ack, 0);
  }

  public retryOutstanding(): void {
    for (const [sequence, payload] of this.inFlight) this.emit(FrameType.Data, sequence, payload);
  }

  public get contiguousAck(): number {
    return this.highestAck;
  }

  public get outstanding(): number {
    return this.inFlight.size;
  }

  private deliver(sequence: number, payload: Uint8Array): void {
    this.nextReceiveSeq = (sequence + 1) >>> 0;
    this.highestAck = sequence;
    this.onDeliver(payload);
  }

  private emit(type: number, sequence: number, payload?: Uint8Array): void {
    const sendSeq = type === FrameType.Ack ? this.highestAck : sequence;
    this.sendDatagram(encodeUdpPacket(type, sendSeq, this.random, payload));
  }
}
