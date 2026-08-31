export const MAX_FRAME_LENGTH = 65536;
export const UDP_HEADER_LENGTH = 15;

export const FrameType = {
  Data: 1,
  Ack: 2,
  Hello: 3,
  Heartbeat: 4,
  Error: 5
} as const;

export type FrameTypeValue = (typeof FrameType)[keyof typeof FrameType];

export interface UdpPacket {
  type: number;
  seq: number;
  ack: number;
  payload: Uint8Array;
}

export function encodeTcpFrame(body: Uint8Array): Uint8Array {
  if (body.length === 0 || body.length > MAX_FRAME_LENGTH) {
    throw new RangeError(`Invalid TCP frame body length: ${body.length}`);
  }
  const result = new Uint8Array(body.length + 4);
  new DataView(result.buffer).setUint32(0, body.length);
  result.set(body, 4);
  return result;
}

export function tryReadTcpFrame(buffer: Uint8Array): { body: Uint8Array; rest: Uint8Array } | undefined {
  if (buffer.length < 4) return undefined;
  const length = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength).getUint32(0);
  if (length === 0 || length > MAX_FRAME_LENGTH) throw new RangeError(`Invalid TCP frame length: ${length}`);
  if (buffer.length < 4 + length) return undefined;
  return { body: buffer.slice(4, 4 + length), rest: buffer.slice(4 + length) };
}

export function encodeUdpPacket(type: number, seq: number, ack: number, payload?: Uint8Array): Uint8Array {
  const body = payload ?? new Uint8Array();
  if (body.length > MAX_FRAME_LENGTH - UDP_HEADER_LENGTH) throw new RangeError("UDP payload is too large");
  const result = new Uint8Array(UDP_HEADER_LENGTH + body.length);
  result[0] = 0x58;
  result[1] = 0x53;
  result[2] = type;
  const view = new DataView(result.buffer);
  view.setUint32(3, seq >>> 0);
  view.setUint32(7, ack >>> 0);
  view.setUint32(11, body.length);
  result.set(body, UDP_HEADER_LENGTH);
  return result;
}

export function decodeUdpPacket(data: Uint8Array): UdpPacket | undefined {
  if (data.length < UDP_HEADER_LENGTH || data[0] !== 0x58 || data[1] !== 0x53) return undefined;
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const length = view.getUint32(11);
  if (length > MAX_FRAME_LENGTH - UDP_HEADER_LENGTH || data.length < UDP_HEADER_LENGTH + length) return undefined;
  return {
    type: data[2] ?? 0,
    seq: view.getUint32(3),
    ack: view.getUint32(7),
    payload: data.slice(UDP_HEADER_LENGTH, UDP_HEADER_LENGTH + length)
  };
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
