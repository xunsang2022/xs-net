package com.game;

import com.game.message.rpc.GameRpcEnvelopeFactory;
import com.game.message.rpc.GameRpcMessages;
import com.xs.net.message.MessageAble;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DbSyncApplication {
    public static void main(String[] args) {
        // 手动初始化消息注册表，避免依赖 Spring 上下文时序（与 GameLogicApplication 一致）
        MessageAble.initialize(GameRpcMessages.INSTANCE, new GameRpcEnvelopeFactory());
        SpringApplication.run(DbSyncApplication.class, args);
    }
}
