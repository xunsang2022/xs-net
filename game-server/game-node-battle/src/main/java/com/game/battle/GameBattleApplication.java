package com.game.battle;

import com.game.battle.actor.BattleCommandIngress;
import com.game.battle.actor.BattleExecutorConfig;
import com.game.battle.actor.BattleExecutorPool;
import com.game.battle.actor.BattleServiceContext;
import com.game.message.actor.logic.BattleMessage;
import com.game.message.rpc.GameRpcMessages;
import com.xs.kernel.actor.node.NodeActor;
import com.xs.kernel.akka.AkkaKit;
import com.xs.kernel.config.GameFrameworkProperties;
import com.xs.net.message.MessageAble;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/** Plain Java entry point for the dedicated battle node. */
public final class GameBattleApplication {
    private GameBattleApplication() {
    }

    public static void main(String[] args) {
        MessageAble.initialize(GameRpcMessages.INSTANCE);

        BattleExecutorConfig config = new BattleExecutorConfig(
                configInt("BATTLE_EXECUTOR_POOL", 8),
                configInt("BATTLE_CONTROL_QUEUE_CAPACITY", 256),
                configInt("BATTLE_MAX_INPUTS_PER_DRAIN", 512),
                configInt("BATTLE_SHUTDOWN_TIMEOUT_MS", 2000),
                configInt("BATTLE_TICK_HZ", 60));
        BattleExecutorPool executorPool = new BattleExecutorPool(config);
        BattleCommandIngress ingress = new BattleCommandIngress(executorPool);
        BattleServiceContext.setBattleCommandIngress(ingress);
        AkkaKit.registerDeliveryInterceptor(BattleMessage.class, ingress);

        GameFrameworkProperties properties = new GameFrameworkProperties();
        properties.setActorBasePackages(new String[]{"com.game.battle.actor"});
        properties.setMessageBasePackages(new String[]{"com.game.message.actor.logic", "com.game.message.actor"});

        Behavior<Void> guardian = Behaviors.setup(context -> {
            context.spawn(NodeActor.create(properties), "node");
            return Behaviors.empty();
        });
        ActorSystem<Void> system = ActorSystem.create(guardian, "game-system");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AkkaKit.unregisterDeliveryInterceptor(BattleMessage.class);
            BattleServiceContext.clear();
            executorPool.close();
            system.terminate();
            system.getWhenTerminated().toCompletableFuture().join();
        }, "battle-shutdown"));

        system.getWhenTerminated().toCompletableFuture().join();
    }

    private static int configInt(String env, int defaultValue) {
        String value = System.getenv(env);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }
}
