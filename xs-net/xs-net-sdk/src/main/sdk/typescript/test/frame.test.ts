import { describe, expect, it } from "vitest";
import { decodeUdpPacket, encodeTcpFrame, encodeUdpPacket, FrameType, tryReadTcpFrame } from "../src/frame.js";

describe("frame codec", () => {
  it("round trips a TCP frame", () => {
    const frame = encodeTcpFrame(new TextEncoder().encode("hello"));
    const decoded = tryReadTcpFrame(frame);
    expect(new TextDecoder().decode(decoded?.body)).toBe("hello");
  });

  it("round trips a UDP packet", () => {
    const packet = decodeUdpPacket(encodeUdpPacket(FrameType.Data, 7, 3, new Uint8Array([1, 2])));
    expect(packet).toEqual({ type: FrameType.Data, seq: 7, ack: 3, payload: new Uint8Array([1, 2]) });
  });
});
