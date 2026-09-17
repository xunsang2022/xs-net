import { NetMessage } from "../generated/net_message.js";

export function encodeNetMessage(rpcNum: number, payload: Uint8Array): Uint8Array {
  return NetMessage.encode(NetMessage.create({ rpcNum, data: payload })).finish();
}

export function decodeNetMessage(data: Uint8Array): { rpcNum: number; data: Uint8Array } {
  const message = NetMessage.decode(data);
  return { rpcNum: message.rpcNum, data: message.data };
}
