package com.xs.kernel.actor;

import com.xs.kernel.model.XsModel;
import lombok.Getter;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

public abstract class SimpleActorServer<Message,M extends XsModel<M>> implements ActorServer<Message,M> {
    /// 脏数据定时刷新消息的唯一键。
    protected static final Object FLUSH_KEY = new Object();
    /// 定时器
    protected final TimerScheduler<Message> timers;
    protected final ActorServices<Message> services;

    @Getter
    protected final M data;

    protected SimpleActorServer(ActorServices<Message> services, TimerScheduler<Message> timers, Object modelId) {
        this.data = this.loadingModel(modelId);
        this.services = services;
        this.timers = timers;
    }
}
