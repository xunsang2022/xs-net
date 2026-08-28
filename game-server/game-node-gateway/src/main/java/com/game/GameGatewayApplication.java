package com.game;

import com.game.comment.config.CommentConfig;
import com.game.message.rpc.GameRpcMessages;
import com.xs.net.message.MessageAble;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Import({CommentConfig.class})
@SpringBootApplication
public class GameGatewayApplication {

    public static void main(String[] args) {
        // 手动初始化消息注册表，避免依赖 Spring 上下文时序（与 GameLogicApplication 一致）
        MessageAble.initialize(GameRpcMessages.INSTANCE);
        //--add-opens java.base/sun.nio.ch=ALL-UNNAMED
        SpringApplication.run(GameGatewayApplication.class, args);
    }

}
