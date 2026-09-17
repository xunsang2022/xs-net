import { describe, expect, it } from "vitest";
import { CSLogin } from "../generated/comment.js";

describe("generated protobuf", () => {
  it("round trips a generated message", () => {
    const original = CSLogin.create({ playerId: "player-1001", playerName: "Alice", sessionId: "session-1" });
    const decoded = CSLogin.decode(CSLogin.encode(original).finish());

    expect(decoded).toMatchObject(original);
  });
});
