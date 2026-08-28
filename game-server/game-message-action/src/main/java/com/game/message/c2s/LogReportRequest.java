package com.game.message.c2s;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

@Rpc(kind = Rpc.Kind.MESSAGE, number = 2100)
public record LogReportRequest(
        @ProtoField(number = 1) String sessionId,
        @ProtoField(number = 2) Integer logLevel,
        @ProtoField(number = 3) Integer logType,
        @ProtoField(number = 4) String content
) {
}
