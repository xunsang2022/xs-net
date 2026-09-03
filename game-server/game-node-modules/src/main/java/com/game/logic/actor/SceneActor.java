package com.game.logic.actor;

import com.game.logic.scene.SceneMembership;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.actor.logic.SceneMessage;
import com.game.message.scene.PlayerSceneChanged;
import com.game.message.scene.SceneJoinCommand;
import com.game.message.scene.SceneLeaveCommand;
import com.xs.core.actor.RpcActor;
import com.xs.core.actor.node.ActorDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

@Slf4j
@ActorDispatcher("pekko.actor.battle-dispatcher")
public final class SceneActor extends RpcActor<SceneMessage> {

    private final SceneMembership membership = new SceneMembership();

    private SceneActor(ActorContext<SceneMessage> context, String entityId) {
        super(context, entityId);
    }

    public static Behavior<SceneMessage> create(String entityId) {
        return Behaviors.setup(context -> new SceneActor(context, entityId));
    }

    @Override
    public Receive<SceneMessage> createReceive() {
        return newRpcReceiveBuilder()
                .onMessage(SceneJoinCommand.class, this::onJoin)
                .onMessage(SceneLeaveCommand.class, this::onLeave)
                .build();
    }

    private Behavior<SceneMessage> onJoin(SceneJoinCommand command) {
        if (membership.join(command.playerId())) {
            sendRpc(PlayerMessage.class, new PlayerSceneChanged(entityId(), true), String.valueOf(command.playerId()));
            log.info("Player joined scene: sceneId={}, playerId={}, members={}",
                    entityId(), command.playerId(), membership.size());
        }
        return this;
    }

    private Behavior<SceneMessage> onLeave(SceneLeaveCommand command) {
        if (membership.leave(command.playerId())) {
            sendRpc(PlayerMessage.class, new PlayerSceneChanged(entityId(), false), String.valueOf(command.playerId()));
            log.info("Player left scene: sceneId={}, playerId={}, members={}",
                    entityId(), command.playerId(), membership.size());
        }
        return this;
    }
}
