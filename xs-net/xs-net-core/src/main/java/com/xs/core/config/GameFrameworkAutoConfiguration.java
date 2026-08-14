package com.xs.core.config;

import akka.actor.typed.ActorSystem;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import com.xs.core.actor.node.NodeActor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Game Framework 自动配置类
 */
@AutoConfiguration
@EnableConfigurationProperties(GameFrameworkProperties.class)
@ComponentScan(basePackages = "com.xs.core")
public class GameFrameworkAutoConfiguration {

    /**
     * 创建 ClusterSharding bean
     */
    @Bean
    @ConditionalOnClass(name = "akka.cluster.sharding.typed.javadsl.ClusterSharding")
    public ClusterSharding clusterSharding(ActorSystem<Void> actorSystem) {
        return ClusterSharding.get(actorSystem);
    }

    /**
     * 创建并配置 ActorSystem bean，供 Spring Boot 应用使用
     * NodeActor 将在 ActorRegisterManager 中通过 SpawnProtocol 创建
     */
    @Bean
    @ConditionalOnClass(name = "akka.actor.typed.ActorSystem")
    public ActorSystem<Void> actorSystem(GameFrameworkProperties  properties) {
        akka.actor.typed.Behavior<Void> guardianBehavior = 
            akka.actor.typed.javadsl.Behaviors.setup(context -> {
                // 在守护者 Actor 中创建 NodeActor
                context.spawn(
                    NodeActor.create(properties),
                    "node"
                );
                return akka.actor.typed.javadsl.Behaviors.empty();
            });
        
        return ActorSystem.create(guardianBehavior, "game-system");
    }


}
