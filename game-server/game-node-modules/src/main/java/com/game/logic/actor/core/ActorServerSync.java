package com.game.logic.actor.core;

import com.game.eventsourcing.sync.DBSyncEngineRegistry;
import com.game.logic.context.LogicContext;
import com.xs.kernel.model.XsModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 通用 Actor 状态同步组件，负责把多个 XsModel 的状态异步落库。
 *
 * <p>一个 Actor 可能有多个 ActorServer 组件，每个组件管理不同的 XsModel 数据实体。
 * ActorServerSync 统一管理这些模型的持久化操作。</p>
 *
 * <p>职责包括：
 * <ul>
 *   <li>Actor 销毁时异步落库，并在全部确认写入后回调（供调用方清理 Redis 缓存）</li>
 *   <li>Actor 停止时补偿持久化</li>
 * </ul>
 *
 * <p>不承担在线期间的周期刷写。落库优先走 db-io 池的立即写；池已满或写入未确认时
 * 降级到 {@link DBSyncEngineRegistry} 的有界缓冲批写，保证状态不丢。</p>
 */
@Slf4j
public final class ActorServerSync {
    /**
     * 注册的模型列表。
     * <p>并发前提：本组件的全部访问（构造期 addModel、persistAsync、persistAllStopped）
     * 均只在所属 Actor 的单线程中串行执行——addModel 在 Actor 构造期调用，其余在消息处理或 PostStop 信号中调用，
     * 不存在跨线程访问，因此使用非线程安全的 ArrayList 是安全的。</p>
     */
    private final List<ModelEntry<?>> models = new ArrayList<>();

    /**
     * 注册一个模型到同步组件。
     *
     * @param entityType    实体类型标识
     * @param entityId      实体 ID
     * @param modelProvider 模型提供者
     * @param <M>           模型类型
     * @return 当前同步组件，支持链式调用
     */
    public <M extends XsModel<M>> ActorServerSync addModel(
            String entityType,
            String entityId,
            Supplier<M> modelProvider
    ) {
        models.add(new ModelEntry<>(entityType, entityId, modelProvider));
        return this;
    }

    /**
     * 异步持久化所有注册模型；仅当全部由数据库确认写入后才执行 {@code onPersisted}。
     *
     * <p>调用方应先用 Redis 写入最终状态（持久化兜底），再调用本方法；只有当
     * {@code onPersisted} 被触发时，才可安全清理 Redis 缓存。任何未确认写入
     * （乐观锁冲突、异常、线程池饱和）都会转入有界缓冲批写重试，且不触发回调，
     * 从而保留 Redis 中的副本。</p>
     *
     * @param onPersisted 全部确认写入后的回调，可为 null
     */
    public void persistAsync(Runnable onPersisted) {
        DBSyncEngineRegistry registry = LogicContext.getDbSyncEngineRegistry();
        ExecutorService executor = LogicContext.getDbIoExecutor();
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(models.size());
        for (ModelEntry<?> entry : models) {
            futures.add(saveEntryAsync(entry, registry, executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ignored, error) -> {
            boolean allPersisted = error == null
                    && futures.stream().allMatch(future -> Boolean.TRUE.equals(future.getNow(false)));
            if (allPersisted && onPersisted != null) {
                onPersisted.run();
            }
        });
    }

    /**
     * Actor 停止时补偿持久化所有注册的模型。
     */
    public void persistAllStopped() {
        DBSyncEngineRegistry registry = LogicContext.getDbSyncEngineRegistry();
        ExecutorService executor = LogicContext.getDbIoExecutor();
        for (ModelEntry<?> entry : models) {
            saveEntryAsync(entry, registry, executor);
        }
    }

    /**
     * 落库单个模型：优先走 db-io 池立即写；只要未由数据库确认写入（乐观锁冲突、异常、
     * 线程池饱和），一律转入有界缓冲批写重试，保证最终状态必然进入重试通道直至落库。
     *
     * <p>返回 false 表示此刻 DB 未确认（可能已入缓冲待重试），调用方据此保留 Redis 副本，
     * 避免在落库确认前丢数据。</p>
     */
    private <M extends XsModel<M>> CompletableFuture<Boolean> saveEntryAsync(
            ModelEntry<M> entry,
            DBSyncEngineRegistry registry,
            ExecutorService executor
    ) {
        M model = entry.modelProvider.get();
        Map<String, String> state = Map.copyOf(model.toHash());
        long version = model.getVersionValue();
        long entityId = Long.parseLong(entry.entityId);

        try {
            return CompletableFuture.supplyAsync(() -> {
                        if (registry.saveNow(entry.entityType, entityId, state, version)) {
                            return true;
                        }
                        // 乐观锁冲突等未确认：转入有界缓冲，由引擎定时重试直至落库
                        bufferForRetry(registry, entry.entityType, entityId, state, version);
                        return false;
                    }, executor)
                    .exceptionally(error -> {
                        log.error("Failed to save entity, fallback to retry buffer: entityType={}, entityId={}",
                                entry.entityType, entry.entityId, error);
                        bufferForRetry(registry, entry.entityType, entityId, state, version);
                        return false;
                    });
        } catch (RejectedExecutionException exception) {
            // db-io 池已满：转入有界缓冲，由其定时批写 + 重试落库，避免静默丢弃
            bufferForRetry(registry, entry.entityType, entityId, state, version);
            return CompletableFuture.completedFuture(false);
        }
    }

    /** 把待落库状态转入有界缓冲批写，由其定时重试直至落库；入队失败（缓冲已满）记录错误。 */
    private void bufferForRetry(
            DBSyncEngineRegistry registry,
            String entityType,
            long entityId,
            Map<String, String> state,
            long version
    ) {
        if (!registry.submit(entityType, entityId, state, version)) {
            log.error("Unable to buffer entity state for retry, sync buffer full: entityType={}, entityId={}",
                    entityType, entityId);
        }
    }

    /** 模型注册条目。 */
    private record ModelEntry<M extends XsModel<M>>(
            String entityType,
            String entityId,
            Supplier<M> modelProvider
    ) {
    }
}
