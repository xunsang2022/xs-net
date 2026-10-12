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
 * 通用 Actor 状态同步组件，负责将多个 XsModel 脏数据同步到数据库。
 *
 * <p>一个 Actor 可能有多个 ActorServer 组件，每个组件管理不同的 XsModel 数据实体。
 * ActorServerSync 统一管理这些模型的持久化操作。</p>
 *
 * <p>职责包括：
 * <ul>
 *   <li>检查所有注册模型的脏字段并提交到数据库同步引擎</li>
 *   <li>Actor 销毁时异步持久化所有模型的最终状态</li>
 *   <li>Actor 停止时补偿持久化</li>
 * </ul>
 *
 * <p>定时器由调用方自行管理，本组件只提供数据持久化操作方法。</p>
 */
@Slf4j
public final class ActorServerSync {
    /** 注册的模型列表。 */
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

    /** 处理脏数据刷新：遍历所有注册模型，检查并刷写脏字段。 */
    public void onFlushDirty() {
        for (ModelEntry<?> entry : models) {
            flushModel(entry);
        }
    }

    /** 刷写单个模型的脏数据。 */
    private <M extends XsModel<M>> void flushModel(ModelEntry<M> entry) {
        M model = entry.modelProvider.get();
        if (!model.hasDirtyFields()) {
            return;
        }
        Map<String, String> dirtySnapshot = Map.copyOf(model.toHash(model.getDirtyFields()));
        Map<String, String> fullSnapshot = Map.copyOf(model.toHash());
        long version = model.getVersionValue();

        DBSyncEngineRegistry registry = LogicContext.getDbSyncEngineRegistry();
        boolean submitted = registry.submit(entry.entityType, Long.parseLong(entry.entityId), fullSnapshot, version);
        if (submitted) {
            model.clearDirtyMatching(dirtySnapshot);
        } else {
            log.warn("Dirty flush retained because DB sync queue is full: entityType={}, entityId={}, fields={}",
                    entry.entityType, entry.entityId, dirtySnapshot.keySet());
        }
    }

    /**
     * 将所有注册的模型持久化到数据库（Actor 销毁时调用）。
     */
    public void destroyAll() {
        DBSyncEngineRegistry registry = LogicContext.getDbSyncEngineRegistry();
        ExecutorService executor = LogicContext.getDbIoExecutor();
        for (ModelEntry<?> entry : models) {
            saveModelAsync(entry, registry, executor);
        }
    }

    /**
     * Actor 停止时补偿持久化所有注册的模型。
     */
    public void persistAllStopped() {
        DBSyncEngineRegistry registry = LogicContext.getDbSyncEngineRegistry();
        ExecutorService executor = LogicContext.getDbIoExecutor();
        for (ModelEntry<?> entry : models) {
            saveModelAsync(entry, registry, executor);
        }
    }

    /** 异步保存单个模型。 */
    private <M extends XsModel<M>> void saveModelAsync(
            ModelEntry<M> entry,
            DBSyncEngineRegistry registry,
            ExecutorService executor
    ) {
        M model = entry.modelProvider.get();
        Map<String, String> state = Map.copyOf(model.toHash());
        long version = model.getVersionValue();

        try {
            CompletableFuture.supplyAsync(
                    () -> registry.saveNow(entry.entityType, Long.parseLong(entry.entityId), state, version),
                    executor
            ).whenComplete((success, error) -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    log.error("Failed to save entity to database: entityType={}, entityId={}",
                            entry.entityType, entry.entityId, error);
                }
            });
        } catch (RejectedExecutionException exception) {
            log.error("Unable to schedule database save: entityType={}, entityId={}",
                    entry.entityType, entry.entityId, exception);
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
