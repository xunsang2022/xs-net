package com.game.message.s2c;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

@Rpc(kind = Rpc.Kind.MESSAGE, number = 2101)
public record LogReportResponse(
        @ProtoField(number = 1) String sessionId,
        @ProtoField(number = 2) Integer result,
        @ProtoField(number = 3) Long logId
) {
}
