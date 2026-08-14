package com.game.login.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.game.enums.LogLevel;
import com.game.enums.LogType;
import com.game.message.action.CS_LOGIN;
import com.game.message.action.CS_Logout;
import com.game.message.action.SC_Logout;
import com.game.message.actor.*;
import com.game.message.actor.logic.BackpackCleanupMessage;
import com.game.message.actor.logic.BackpackInitMessage;
import com.game.message.actor.logic.C2S;
import com.game.message.actor.login.PlayerMessage;
import com.game.po.player.user.User;
import com.game.po.player.user.UserRepository;
import com.xs.core.akka.AkkaKit;
import com.xs.core.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.System;
import java.util.Optional;

@Slf4j
public class PlayerActor extends AbstractBehavior<PlayerMessage> {
    private final String entityId;
    private final UserRepository dao = SpringUtil.getBean(UserRepository.class);

    private User user;

    public PlayerActor(ActorContext<PlayerMessage> context, String name) {
        super(context);
        Optional<User> byName = dao.findByName(name);
        if (byName.isPresent()) {
            this.user = byName.get();
        } else {
            this.user = new User();
            this.user.setName(name);
            this.user = dao.save(this.user);
        }
        this.entityId = this.user.getId().toString();
    }

    public static Behavior<PlayerMessage> create(String entityId) {
        return Behaviors.setup(context -> new PlayerActor(context, entityId));
    }

    @Override
    public Receive<PlayerMessage> createReceive() {
        return newReceiveBuilder()
                .onMessage(CS_LOGIN.class, this::login)
                .onMessage(CS_Logout.class, this::logout)
                .onMessage(BackpackInitAckMessage.class, this::handleBackpackInitAck)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    /**
     * 处理玩家登录
     */
    private Behavior<PlayerMessage> login(CS_LOGIN csLogin) {
        log.info("PlayerActor login request: playerId={}, playerName={}, sessionId={}",
                csLogin.getPlayerId(), csLogin.getPlayerName(), csLogin.getSessionId());

        // 加载或创建用户数据
        if (this.user == null) {
            Long id = Long.valueOf(this.entityId);
            Optional<User> existingUser = dao.findById(id);

            if (existingUser.isPresent()) {
                this.user = existingUser.get();
                log.info("Loaded existing user: {}", id);
            } else {
                // 创建新用户，手动设置id，name由唯一约束保护
                this.user = new User();
                this.user.setId(id);
                this.user.setName(csLogin.getPlayerName());
                this.user = dao.save(user);
                log.info("Created new user with id: {}", this.user.getId());
            }
        }

        // 更新用户名称（如果有变化）
        if (csLogin.getPlayerName() != null && !csLogin.getPlayerName().equals(this.user.getName())) {
            this.user.setName(csLogin.getPlayerName());
            dao.save(this.user);
        }

        // 记录上线日志到 LogActor
        Long playerId = Long.parseLong(csLogin.getPlayerId());
        logToLogActor(LogLevel.MEDIUM, LogType.LOGIN, "Player online", playerId);

        // 标记为在线
        initializeBackpack();
        return this;
    }

    /**
     * 处理玩家登出
     */
    private Behavior<PlayerMessage> logout(CS_Logout csLogout) {
        log.info("PlayerActor logout request: {}", this.entityId);

        // 发送登出响应
        AkkaKit.tell(S2C.class, new SC_Logout(), csLogout.getSessionId());
        log.info("Logout successful for player: {}", this.entityId);

        // 停止 Actor
        return Behaviors.stopped();
    }

    /**
     * Actor 销毁时的生命周期方法
     * 在 Actor 停止前执行清理工作
     */
    private Behavior<PlayerMessage> onPostStop() {
        log.info("PlayerActor destroyed: {}", this.entityId);

        if (this.user != null) {
            try {
                dao.save(this.user);
                log.info("User data saved for player: {}", this.entityId);
            } catch (Exception e) {
                log.error("Failed to save user data for player: {}", this.entityId, e);
            }
        }

        // 记录下线日志
        if (this.user != null) {
            logToLogActor(LogLevel.MEDIUM, LogType.LOGIN, "Player offline", this.user.getId());
        }

        cleanupBackpack();
        return Behaviors.same();
    }

    private void initializeBackpack() {
        try {
            long playerId = Long.parseLong(this.entityId);
            BackpackInitMessage message = new BackpackInitMessage(playerId, System.currentTimeMillis());
            AkkaKit.tell(C2S.class, message, this.entityId);
            log.info("BackpackActor initialization sent for player: {}", this.entityId);
        } catch (Exception e) {
            log.error("Failed to initialize backpack for player: {}", this.entityId, e);
        }
    }

    private void cleanupBackpack() {
        try {
            long playerId = Long.parseLong(this.entityId);
            BackpackCleanupMessage message = new BackpackCleanupMessage(playerId);
            AkkaKit.tell(C2S.class, message, this.entityId);
            log.info("BackpackActor cleanup sent for player: {}", this.entityId);
        } catch (Exception e) {
            log.error("Failed to cleanup backpack for player: {}", this.entityId, e);
        }
    }

    private Behavior<PlayerMessage> handleBackpackInitAck(BackpackInitAckMessage msg) {
        Long playerId = Long.valueOf(this.entityId);
        if (msg.getPlayerId() != (playerId)) {
            log.warn("BackpackInitAckMessage playerId mismatch: expected={}, got={}", playerId, msg.getPlayerId());
            return this;
        }

        if (msg.getStatus() == 0) {
            log.info("BackpackActor initialized successfully for player: {}, itemCount={}", this.entityId, msg.getItemCount());
        } else {
            log.warn("BackpackActor initialization failed for player: {}, status={}", this.entityId, msg.getStatus());
        }

        return this;
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        log.info("PlayerActor clone: {}", this.entityId);
        return super.clone();
    }

    /**
     * 发送日志到 LogActor
     */
    private void logToLogActor(LogLevel level, LogType type, String message, Long playerId) {
        try {
            // 通过 Akka 集群获取或创建 LogActor
            String logActorEntityId = "log-" + playerId;
            LogRecordMessage logMessage = new LogRecordMessage(level.ordinal(), type.ordinal(), message, playerId);
            AkkaKit.tell(XSSystem.class, logMessage, logActorEntityId);
        } catch (Exception e) {
            log.error("Failed to send log to LogActor: playerId={}, message={}", playerId, message, e);
        }
    }
}

