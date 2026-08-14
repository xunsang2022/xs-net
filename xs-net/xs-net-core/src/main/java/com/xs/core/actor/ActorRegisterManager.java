package com.xs.core.actor;

import com.xs.core.actor.node.NodeActor;
import com.xs.core.config.GameFrameworkProperties;
import lombok.NonNull;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Actor 注册管理器
 * 自动扫描带有 @ActorRegister 注解的类，并根据当前节点角色注册到集群分片系统
 */
@Configuration
public class ActorRegisterManager implements ApplicationListener<@NonNull ContextRefreshedEvent> {
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        GameFrameworkProperties properties = event.getApplicationContext().getBean(GameFrameworkProperties.class);
        NodeActor.create(properties);
    }
}
