package com.game.logic.redis;

import com.game.message.actor.db.DbSyncCmd;
import com.game.message.actor.db.DbSyncOp;
import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.DbSyncMessage;
import com.xs.core.akka.AkkaKit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RedisDbSyncNotifier implements DbSyncNotifier {

    @Override
    public void notify(String playerId, String op) {
        try {
            DbSyncOp syncOp = DbSyncOp.valueOf(op);
            DbSyncMessage message = DbSyncMessage.newBuilder()
                    .setPlayerId(Long.parseLong(playerId))
                    .setOp(syncOp.ordinal())
                    .build();
            AkkaKit.tell(DbSyncCmd.class, GameRpcMessages.wrap(message), playerId);
        } catch (Exception e) {
            log.error("Failed to notify db-sync: playerId={}, op={}", playerId, op, e);
        }
    }
}
