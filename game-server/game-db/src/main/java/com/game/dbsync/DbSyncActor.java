package com.game.dbsync;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import com.game.message.actor.db.DbSyncCmd;
import com.game.message.actor.db.DbSyncLoadMessage;
import com.game.message.actor.db.DbSyncLoadResultMessage;
import com.game.message.actor.db.DbSyncMessage;
import com.game.message.actor.db.DbSyncOp;
import com.game.message.actor.logic.PlayerMessage;
import com.xs.core.akka.AkkaKit;
import com.xs.core.spring.SpringUtil;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DbSyncActor extends AbstractBehavior<DbSyncCmd> {
    private final DbSyncLedger ledger = new DbSyncLedger();
    private final DbSyncStore store;
    private final DbSyncProperties properties;
    private final DbSyncMetrics metrics;
    private final TimerScheduler<DbSyncCmd> timers;
    private final ExecutorService flushExecutor;
    private final AtomicInteger inFlight = new AtomicInteger();

    private DbSyncActor(
            ActorContext<DbSyncCmd> context,
            TimerScheduler<DbSyncCmd> timers,
            DbSyncStore store,
            DbSyncProperties properties,
            DbSyncMetrics metrics
    ) {
        super(context);
        this.timers = timers;
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
        int concurrency = Math.max(1, properties.getFlushConcurrency());
        this.flushExecutor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "db-sync-flush");
            thread.setDaemon(true);
            return thread;
        });
        metrics.updateFlushConcurrency(concurrency);
        timers.startTimerWithFixedDelay(
                new FlushTick(),
                Duration.ofSeconds(Math.max(1, properties.getBgsaveSeconds())));
    }

    public static Behavior<DbSyncCmd> create() {
        DbSyncStore store = SpringUtil.getBean(DbSyncStore.class);
        DbSyncProperties properties = SpringUtil.getBean(DbSyncProperties.class);
        DbSyncMetrics metrics = SpringUtil.getBean(DbSyncMetrics.class);
        return Behaviors.withTimers(timers ->
                Behaviors.setup(context -> new DbSyncActor(context, timers, store, properties, metrics)));
    }

    public static Behavior<DbSyncCmd> create(String entityId) {
        return create();
    }

    @Override
    public Receive<DbSyncCmd> createReceive() {
        return newReceiveBuilder()
                .onMessage(DbSyncMessage.class, this::onDirty)
                .onMessage(DbSyncLoadMessage.class, this::onLoad)
                .onMessage(FlushTick.class, this::onTick)
                .onMessage(ShardResult.class, this::onShardResult)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<DbSyncCmd> onDirty(DbSyncMessage message) {
        Long playerId = message.getPlayerId();
        if (playerId == null) {
            log.warn("DbSyncMessage without playerId, op={}", message.getOp());
            return this;
        }
        ledger.mark(playerId, message.getOp());
        metrics.updateDirtyDepth(ledger.size());
        if (message.getOp() == DbSyncOp.DESTROY) {
            flushSync(List.of(playerId));
        }
        return this;
    }

    private Behavior<DbSyncCmd> onLoad(DbSyncLoadMessage message) {
        Long playerId = message.getPlayerId();
        try {
            Map<String, String> fields = store.loadOrCreatePlayer(playerId);
            store.writePlayerHashAndIndex(playerId, fields);
            sendLoadResult(playerId, true, fields);
        } catch (Exception e) {
            log.error("Failed to load or create player {}", playerId, e);
            metrics.recordLoadFailure();
            sendLoadResult(playerId, false, Map.of());
        }
        return this;
    }

    private void sendLoadResult(Long playerId, boolean success, Map<String, String> fields) {
        DbSyncLoadResultMessage result = new DbSyncLoadResultMessage(playerId, success, fields);
        try {
            // The shared contract is expected to make DbSyncLoadResultMessage a PlayerMessage subtype.
            AkkaKit.tell(PlayerMessage.class, (PlayerMessage) result, String.valueOf(playerId));
        } catch (Exception e) {
            log.error("Failed to send load result for player {}", playerId, e);
        }
    }

    private Behavior<DbSyncCmd> onTick(FlushTick tick) {
        triggerAsyncFlush(ledger.snapshot());
        return this;
    }

    private void triggerAsyncFlush(List<Long> players) {
        if (players.isEmpty() || inFlight.get() > 0) {
            return;
        }
        int concurrency = Math.max(1, properties.getFlushConcurrency());
        List<List<Long>> shards = partition(players, concurrency);
        inFlight.set(shards.size());
        for (int i = 0; i < shards.size(); i++) {
            List<Long> shard = shards.get(i);
            int shardIndex = i;
            CompletableFuture<ShardOutcome> future =
                    CompletableFuture.supplyAsync(() -> flushShard(shard), flushExecutor);
            getContext().pipeToSelf(future, (outcome, failure) ->
                    new ShardResult(shardIndex, failure == null ? outcome : ShardOutcome.failed(shard, failure)));
        }
    }

    private ShardOutcome flushShard(List<Long> shard) {
        Timer.Sample sample = metrics.startFlushRound();
        List<Long> succeeded = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        Map<Long, Map<String, String>> recordsByPlayer = new LinkedHashMap<>();
        try {
            for (Long playerId : shard) {
                try {
                    Map<String, String> fields = store.loadPlayerHash(playerId);
                    if (fields == null || fields.isEmpty()) {
                        failed.add(playerId);
                    } else {
                        recordsByPlayer.put(playerId, fields);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read player {} hash", playerId, e);
                    failed.add(playerId);
                }
            }

            if (!recordsByPlayer.isEmpty()) {
                try {
                    store.batchUpsertPlayers(new ArrayList<>(recordsByPlayer.values()));
                    succeeded.addAll(recordsByPlayer.keySet());
                } catch (Exception e) {
                    log.error("Batch upsert failed for {} players", recordsByPlayer.size(), e);
                    failed.addAll(recordsByPlayer.keySet());
                }
            }

            for (Long playerId : succeeded) {
                try {
                    store.markSynced(playerId);
                } catch (Exception e) {
                    log.warn("Failed to clear dirty marker for player {}", playerId, e);
                }
                ledger.synced(playerId);
            }
            metrics.updateDirtyDepth(ledger.size());
            return new ShardOutcome(succeeded, failed, null);
        } finally {
            metrics.stopFlushRound(sample);
        }
    }

    private void flushSync(List<Long> players) {
        if (players.isEmpty()) {
            return;
        }
        ShardOutcome outcome = flushShard(players);
        if (!outcome.failed.isEmpty()) {
            metrics.recordFlushFailure();
        }
    }

    private Behavior<DbSyncCmd> onShardResult(ShardResult result) {
        inFlight.decrementAndGet();
        if (result.outcome().failure != null || !result.outcome().failed.isEmpty()) {
            metrics.recordFlushFailure();
        }
        metrics.updateDirtyDepth(ledger.size());
        return this;
    }

    private Behavior<DbSyncCmd> onPostStop() {
        log.info("DbSyncActor stopping, flushing remaining dirty players: {}", ledger.snapshot());
        flushSync(ledger.snapshot());
        flushExecutor.shutdown();
        return Behaviors.same();
    }

    private static List<List<Long>> partition(List<Long> players, int shardCount) {
        List<List<Long>> shards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            shards.add(new ArrayList<>());
        }
        for (Long playerId : players) {
            shards.get((int) Math.floorMod(playerId, shardCount)).add(playerId);
        }
        List<List<Long>> nonEmpty = new ArrayList<>();
        for (List<Long> shard : shards) {
            if (!shard.isEmpty()) {
                nonEmpty.add(shard);
            }
        }
        return nonEmpty;
    }

    private static final class FlushTick implements DbSyncCmd {
    }

    private static final class ShardResult implements DbSyncCmd {
        private final int shardIndex;
        private final ShardOutcome outcome;

        private ShardResult(int shardIndex, ShardOutcome outcome) {
            this.shardIndex = shardIndex;
            this.outcome = outcome;
        }

        private ShardOutcome outcome() {
            return outcome;
        }
    }

    private static final class ShardOutcome {
        private final List<Long> succeeded;
        private final List<Long> failed;
        private final Throwable failure;

        private ShardOutcome(List<Long> succeeded, List<Long> failed, Throwable failure) {
            this.succeeded = succeeded;
            this.failed = failed;
            this.failure = failure;
        }

        private static ShardOutcome failed(List<Long> players, Throwable failure) {
            return new ShardOutcome(List.of(), players, failure);
        }
    }
}
