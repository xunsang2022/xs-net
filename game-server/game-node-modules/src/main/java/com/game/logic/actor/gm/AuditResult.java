package com.game.logic.actor.gm;

import com.game.message.actor.logic.GMMessage;
import com.game.message.management.GmKickPlayerRequest;

/** GM 请求审计完成后返回给 {@link GMActor} 的内部结果消息。 */
final class AuditResult implements GMMessage {
    final GmKickPlayerRequest request;
    final Boolean success;
    final Throwable error;

    AuditResult(GmKickPlayerRequest request, Boolean success, Throwable error) {
        this.request = request;
        this.success = success;
        this.error = error;
    }
}
