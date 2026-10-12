package com.xs.kernel.actor;

import com.xs.kernel.model.XsModel;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;

/// 游戏 Actor的服务子类 必备能力
public interface ActorServer<Message,M extends XsModel<M>> {

    /// actor 组件服务消息注册
    default void registerHandlers(ReceiveBuilder<Message> builder) {}

    /// 配置获取存储实体功能
    M getData();

    /// 加载数据
    M loadingModel(Object modelId);
}
