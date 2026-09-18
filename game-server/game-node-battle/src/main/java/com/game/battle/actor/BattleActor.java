package com.game.battle.actor;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.battle.BattleAttackCommand;
import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleEndCommand;
import com.game.message.battle.BattleInputCommand;
import com.game.message.battle.BattleJoinCommand;
import com.game.message.battle.BattleLeaveCommand;
import com.xs.core.actor.RpcActor;
import com.xs.core.actor.node.ActorDispatcher;
import io.micrometer.core.instrument.Metrics;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;

@ActorDispatcher("pekko.actor.battle-control-dispatcher")
public final class BattleActor extends RpcActor<BattleMessage> {
    private static final Logger log = LoggerFactory.getLogger(BattleActor.class);
    private final BattleCommandIngress ingress;

    private BattleActor(
            ActorContext<BattleMessage> context,
            String battleId
    ) {
        super(context, battleId);
        this.ingress = BattleServiceContext.getBattleCommandIngress();
    }

    public static Behavior<BattleMessage> create(String battleId) {
        return Behaviors.setup(context -> new BattleActor(context, battleId));
    }

    @Override
    public Receive<BattleMessage> createReceive() {
        return newRpcReceiveBuilder()
                .onMessage(BattleCreateCommand.class, contextual(this::onCreate))
                .onMessage(BattleJoinCommand.class, contextual(this::onJoin))
                .onMessage(BattleLeaveCommand.class, contextual(this::onLeave))
                .onMessage(BattleInputCommand.class, contextual(this::onInput))
                .onMessage(BattleAttackCommand.class, contextual(this::onAttack))
                .onMessage(BattleEndCommand.class, contextual(this::onEnd))
                .build();
    }

    private Behavior<BattleMessage> onCreate(BattleCreateCommand command) {
        if (command.battleId() == null || command.battleId().isBlank()
                || !Objects.equals(entityId(), command.battleId())) {
            log.warn("Battle creation rejected: entityId={}, commandBattleId={}", entityId(), command.battleId());
            return this;
        }
        ingress.activate(entityId());
        ingress.submit(entityId(), command, currentRequestContext());
        return this;
    }

    private Behavior<BattleMessage> onJoin(BattleJoinCommand command) {
        ingress.submit(entityId(), command, currentRequestContext());
        return this;
    }

    private Behavior<BattleMessage> onLeave(BattleLeaveCommand command) {
        ingress.submit(entityId(), command, currentRequestContext());
        return this;
    }

    private Behavior<BattleMessage> onInput(BattleInputCommand command) {
        ingress.submit(entityId(), command, currentRequestContext());
        return this;
    }

    private Behavior<BattleMessage> onAttack(BattleAttackCommand command) {
        ingress.submit(entityId(), command, currentRequestContext());
        return this;
    }

    private Behavior<BattleMessage> onEnd(BattleEndCommand command) {
        ingress.submit(entityId(), command, currentRequestContext());
        return this;
    }
}
