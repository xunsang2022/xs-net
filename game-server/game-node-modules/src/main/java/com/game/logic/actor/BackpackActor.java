package com.game.logic.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.xs.core.spring.SpringUtil;
import com.xs.core.akka.AkkaKit;
import com.game.message.action.BackpackItemInfo;
import com.game.message.action.CS_BACKPACK_INFO;
import com.game.message.action.CS_USE_ITEM;
import com.game.message.action.SC_BACKPACK_INFO;
import com.game.message.action.SC_USE_ITEM;
import com.game.message.actor.logic.BackpackCleanupMessage;
import com.game.message.actor.BackpackInitAckMessage;
import com.game.message.actor.logic.BackpackInitMessage;
import com.game.message.actor.logic.C2S;
import com.game.message.actor.login.PlayerMessage;
import com.game.message.actor.S2C;
import com.game.po.player.backpack.BackpackItem;
import com.game.po.player.backpack.BackpackItemRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class BackpackActor extends AbstractBehavior<C2S> {
    private final String entityId;
    private final BackpackItemRepository dao = SpringUtil.getBean(BackpackItemRepository.class);

    private final Map<Long, BackpackItem> items = new HashMap<>();
    private long lastInitTime = 0;

    public BackpackActor(ActorContext<C2S> context, String entityId) {
        super(context);
        this.entityId = entityId;
        init();
    }

    private void init() {
        Long playerId = Long.valueOf(entityId);
        List<BackpackItem> list = dao.findByPlayerId(playerId);
        for (BackpackItem item : list) {
            items.put(item.getItemId(), item);
        }
        log.info("BackpackActor init: playerId={}, itemCount={}", entityId, items.size());
    }

    public static Behavior<C2S> create(String entityId) {
        return Behaviors.setup(context -> new BackpackActor(context, entityId));
    }

    @Override
    public Receive<C2S> createReceive() {
        return newReceiveBuilder()
                .onMessage(BackpackInitMessage.class, this::handleBackpackInit)
                .onMessage(BackpackCleanupMessage.class, this::handleBackpackCleanup)
                .onMessage(CS_BACKPACK_INFO.class, this::getBackpackInfo)
                .onMessage(CS_USE_ITEM.class, this::useItem)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<C2S> getBackpackInfo(CS_BACKPACK_INFO msg) {
        List<BackpackItemInfo> infoList = items.values().stream()
                .map(item -> new BackpackItemInfo(item.getItemId(), item.getCount()))
                .toList();
        SC_BACKPACK_INFO response = new SC_BACKPACK_INFO(0, infoList, msg.getSessionId());
        log.info("{}",response);
        AkkaKit.tell(S2C.class, response, msg.getSessionId());
        return this;
    }

    private Behavior<C2S> useItem(CS_USE_ITEM msg) {
        BackpackItem item = items.get(msg.getItemId());
        if (item == null || item.getCount() < msg.getCount()) {
            AkkaKit.tell(S2C.class, new SC_USE_ITEM(1, msg.getItemId(), 0L, msg.getSessionId()), msg.getSessionId());
            return this;
        }
        long remain = item.getCount() - msg.getCount();
        item.setCount(remain);
        AkkaKit.tell(S2C.class, new SC_USE_ITEM(0, msg.getItemId(), remain, msg.getSessionId()), msg.getSessionId());
        return this;
    }

    private Behavior<C2S> handleBackpackInit(BackpackInitMessage msg) {
        Long playerId = Long.valueOf(entityId);
        if (msg.getPlayerId()!=(playerId)) {
            log.warn("BackpackInitMessage playerId mismatch: expected={}, got={}", playerId, msg.getPlayerId());
            BackpackInitAckMessage ack = new BackpackInitAckMessage(playerId, 1, items.size());
            AkkaKit.tell(PlayerMessage.class, ack, String.valueOf(playerId));
            return this;
        }

        if (lastInitTime > 0) {
            log.info("BackpackActor already initialized for player: {}, skipping", entityId);
            BackpackInitAckMessage ack = new BackpackInitAckMessage(playerId, 0, items.size());
            AkkaKit.tell(PlayerMessage.class, ack, String.valueOf(playerId));
            return this;
        }

        lastInitTime = System.currentTimeMillis();
        BackpackInitAckMessage ack = new BackpackInitAckMessage(playerId, 0, items.size());
        AkkaKit.tell(PlayerMessage.class, ack, String.valueOf(playerId));
        log.info("BackpackActor initialized for player: {}, itemCount={}", entityId, items.size());

        return this;
    }

    private Behavior<C2S> handleBackpackCleanup(BackpackCleanupMessage msg) {
        long playerId = Long.parseLong(entityId);
        if (msg.getPlayerId() != playerId) {
            log.warn("BackpackCleanupMessage playerId mismatch: expected={}, got={}", playerId, msg.getPlayerId());
            return this;
        }

        log.info("BackpackActor cleanup received for player: {}, stopping", entityId);
        return Behaviors.stopped();
    }

    private Behavior<C2S> onPostStop() {
        log.info("BackpackActor stopping, saving items: playerId={}", entityId);
        dao.saveAll(items.values());
        return Behaviors.same();
    }
}

