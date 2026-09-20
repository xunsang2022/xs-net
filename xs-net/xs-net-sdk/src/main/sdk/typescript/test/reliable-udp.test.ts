import { describe, expect, it } from "vitest";
import { decodeUdpPacket, FrameType } from "../src/frame.js";
import { ReliableUdp } from "../src/reliable-udp.js";

describe("reliable UDP", () => {
  it("buffers out of order packets and delivers them in order", () => {
    const sent: Uint8Array[] = [];
    const delivered: number[] = [];
    const reliable = new ReliableUdp({ sendDatagram: (data) => sent.push(data), onDeliver: (data) => delivered.push(data[0] ?? 0) });
    reliable.receive({ packetId: FrameType.Data, sendSeq: 1, random: 0, body: new Uint8Array([1]) });
    reliable.receive({ packetId: FrameType.Data, sendSeq: 3, random: 0, body: new Uint8Array([3]) });
    reliable.receive({ packetId: FrameType.Data, sendSeq: 2, random: 0, body: new Uint8Array([2]) });
    expect(delivered).toEqual([1, 2, 3]);
    expect(sent.every((data) => decodeUdpPacket(data)?.packetId === FrameType.Ack)).toBe(true);
  });
});
