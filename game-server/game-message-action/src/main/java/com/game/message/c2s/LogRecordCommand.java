package com.game.message.c2s;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 记录需要异步持久化的业务日志消息。 */
@Rpc(kind = Rpc.Kind.MESSAGE, number = 999)
public record LogRecordCommand(
        @ProtoField(number = 1) Integer level,
        @ProtoField(number = 2) Integer type,
        @ProtoField(number = 3) String message,
        @ProtoField(number = 4) Long playerId
) {
}
