export const MAX_FRAME_LENGTH = 65536;
export const PACKET_HEADER_LENGTH = 18;

export const FrameType = {
  Data: 1,
  Ack: 2,
  Hello: 3,
  Heartbeat: 4,
  Error: 5
} as const;

export type FrameTypeValue = (typeof FrameType)[keyof typeof FrameType];

export interface Packet {
  packetId: number;
  sendSeq: number;
  random: number;
  body: Uint8Array;
}

export type UdpPacket = Packet;

export function randomUint32(): number {
  return Math.floor(Math.random() * 0x100000000) >>> 0;
}

export function encodeTcpFrame(body: Uint8Array, packetId = FrameType.Data, sendSeq = 0, random = 0): Uint8Array {
  return encodePacket(packetId, sendSeq, random, body);
}

export function tryReadTcpFrame(buffer: Uint8Array): { packet: Packet; body: Uint8Array; rest: Uint8Array } | undefined {
  if (buffer.length < 4) return undefined;
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const length = view.getUint32(0);
  if (length > MAX_FRAME_LENGTH - PACKET_HEADER_LENGTH) throw new RangeError(`Invalid TCP frame length: ${length}`);
  const frameLength = PACKET_HEADER_LENGTH + length;
  if (buffer.length < frameLength) return undefined;
  const frame = decodePacket(buffer.slice(0, frameLength));
  return { packet: frame, body: frame.body, rest: buffer.slice(frameLength) };
}

export function encodeUdpPacket(packetId: number, sendSeq: number, random: number, body?: Uint8Array): Uint8Array {
  return encodePacket(packetId, sendSeq, random, body ?? new Uint8Array(0));
}

export function decodeUdpPacket(data: Uint8Array): Packet | undefined {
  try {
    return decodePacket(data);
  } catch {
    return undefined;
  }
}

export function encodePacket(packetId: number, sendSeq: number, random: number, body: Uint8Array): Uint8Array {
  if (body.length > MAX_FRAME_LENGTH - PACKET_HEADER_LENGTH) throw new RangeError("Packet body is too large");
  const result = new Uint8Array(PACKET_HEADER_LENGTH + body.length);
  const view = new DataView(result.buffer);
  view.setUint32(0, body.length);
  view.setUint16(4, packetId & 0xffff);
  view.setUint32(6, sendSeq >>> 0);
  view.setUint32(10, random >>> 0);
  view.setUint16(14, crc16(result, 0, 14));
  view.setUint16(16, crc16(body, 0, body.length));
  result.set(body, PACKET_HEADER_LENGTH);
  return result;
}

export function decodePacket(data: Uint8Array): Packet {
  if (data.length < PACKET_HEADER_LENGTH) throw new RangeError("Packet is shorter than the XS header");
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const length = view.getUint32(0);
  if (length > MAX_FRAME_LENGTH - PACKET_HEADER_LENGTH || data.length !== PACKET_HEADER_LENGTH + length) {
    throw new RangeError("Packet length mismatch");
  }
  if (view.getUint16(14) !== crc16(data, 0, 14)) throw new RangeError("Invalid packet header CRC");
  if (view.getUint16(16) !== crc16(data, PACKET_HEADER_LENGTH, length)) {
    throw new RangeError("Invalid packet body CRC");
  }
  return {
    packetId: view.getUint16(4),
    sendSeq: view.getUint32(6),
    random: view.getUint32(10),
    body: data.slice(PACKET_HEADER_LENGTH)
  };
}

export function crc16(data: Uint8Array, offset: number, length: number): number {
  let crc = 0xffff;
  for (let index = offset; index < offset + length; index++) {
    crc ^= (data[index] ?? 0) << 8;
    for (let bit = 0; bit < 8; bit++) crc = ((crc & 0x8000) !== 0 ? (crc << 1) ^ 0x1021 : crc << 1) & 0xffff;
  }
  return crc;
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const result = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}
