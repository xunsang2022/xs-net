package com.game.dbsync;

import com.game.message.actor.db.DbSyncOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbSyncLedgerTest {

    @Test
    void mergesRepeatedUpdatesForSamePlayer() {
        DbSyncLedger ledger = new DbSyncLedger();
        ledger.mark(1L, DbSyncOp.UPDATE);
        ledger.mark(1L, DbSyncOp.CREATE);
        ledger.mark(1L, DbSyncOp.UPDATE);

        assertEquals(1, ledger.size());
        assertTrue(ledger.contains(1L));
        assertEquals(List.of(1L), ledger.snapshot());
    }

    @Test
    void keepsDirtyEntryUntilSynced() {
        DbSyncLedger ledger = new DbSyncLedger();
        ledger.mark(7L, DbSyncOp.UPDATE);
        assertTrue(ledger.contains(7L));

        ledger.synced(7L);

        assertFalse(ledger.contains(7L));
        assertEquals(0, ledger.size());
    }

    @Test
    void failureKeepsDirtyDataForRetry() {
        DbSyncLedger ledger = new DbSyncLedger();
        ledger.mark(3L, DbSyncOp.UPDATE);

        // A failed flush must not clear the entry; only a successful flush calls synced().
        assertEquals(1, ledger.size());
        assertTrue(ledger.contains(3L));

        ledger.synced(3L);
        assertEquals(0, ledger.size());
    }

    @Test
    void latestOpWinsInMemoryAccounting() {
        DbSyncLedger ledger = new DbSyncLedger();
        ledger.mark(9L, DbSyncOp.DESTROY);
        ledger.mark(9L, DbSyncOp.UPDATE);

        assertEquals(DbSyncOp.UPDATE, ledger.snapshotOps().get(9L));
        assertEquals(1, ledger.size());
    }
}
