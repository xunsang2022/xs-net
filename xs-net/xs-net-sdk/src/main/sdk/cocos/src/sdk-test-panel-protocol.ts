import { CSLogin } from "../generated/comment.js";

export interface SdkLoginForm {
  playerId: string;
  playerName: string;
  sessionId: string;
}

export function encodeSdkLogin(form: SdkLoginForm): Uint8Array {
  return CSLogin.encode(CSLogin.create(form)).finish();
}
