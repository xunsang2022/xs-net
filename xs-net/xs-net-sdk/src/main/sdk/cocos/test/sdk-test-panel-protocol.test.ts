import { describe, expect, it } from "vitest";
import { CSLogin } from "../generated/comment";
import { encodeSdkLogin } from "../src/sdk-test-panel-protocol";

describe("SDK test panel protocol", () => {
  it("encodes the login form with the generated protobuf", () => {
    const payload = encodeSdkLogin({
      playerId: "1001",
      playerName: "Alice",
      sessionId: "cocos-test",
    });

    expect(CSLogin.decode(payload)).toEqual({
      playerId: "1001",
      playerName: "Alice",
      sessionId: "cocos-test",
    });
  });
});
