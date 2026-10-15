package com.xs.kernel.actor;

import com.xs.kernel.model.XsModel;
import lombok.Getter;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

public abstract class SimpleActorServer<Message,M extends XsModel<M>> implements ActorServer<Message,M> {
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
