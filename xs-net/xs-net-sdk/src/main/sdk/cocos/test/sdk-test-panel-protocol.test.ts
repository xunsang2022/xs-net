import { describe, expect, it } from "vitest";
import { CSLogin } from "../generated/comment";
import { RPC_DEFINITIONS } from "../src/sdk-test-panel-protocol";

describe("generated SDK test panel protocol", () => {
  it("contains the generated CSLogin definition and encodes its fields", () => {
    const definition = RPC_DEFINITIONS.find((item) => item.protoName === "CSLogin");
    expect(definition).toBeDefined();
    expect(definition?.rpcNum).toBe(1000);
    expect(definition?.fields.map((field) => field.name)).toEqual(["playerId", "playerName", "sessionId"]);

    const payload = definition?.encode({
      playerId: "1001",
      playerName: "Alice",
      sessionId: "cocos-test",
    });
    expect(CSLogin.decode(payload ?? new Uint8Array(0))).toEqual({
      playerId: "1001",
      playerName: "Alice",
      sessionId: "cocos-test",
    });
  });
});
