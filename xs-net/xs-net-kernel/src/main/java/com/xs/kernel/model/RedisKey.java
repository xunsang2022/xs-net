package com.xs.kernel.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 XsModel 实体对应的 Redis key 前缀。
 *
 * <p>用于 {@link com.xs.kernel.actor.SimpleActorServer} 在加载模型时自动构建 Redis key。</p>
 *
 * <pre>
 * {@code @RedisKey("playerActor:")}
 * public class PlayerPO extends GameModel<PlayerPO> { ... }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RedisKey {

    /**
     * Redis key 前缀，如 "playerActor:"。
     *
     * <p>完整的 Redis key 为：前缀 + entityId。</p>
     */
    String value();
}
