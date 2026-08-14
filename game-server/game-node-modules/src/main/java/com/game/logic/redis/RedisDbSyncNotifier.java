package com.game.logic.redis;

import com.game.message.actor.db.DbSyncCmd;
import com.game.message.actor.db.DbSyncMessage;
import com.game.message.actor.db.DbSyncOp;
import com.xs.core.akka.AkkaKit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RedisDbSyncNotifier implements DbSyncNotifier {

    @Override
    public void notify(String playerId, String op) {
        try {
            DbSyncOp syncOp = DbSyncOp.valueOf(op);
            AkkaKit.tell(DbSyncCmd.class, new DbSyncMessage(Long.valueOf(playerId), syncOp), playerId);
        } catch (Exception e) {
            log.error("Failed to notify db-sync: playerId={}, op={}", playerId, op, e);
        }
    }
}
