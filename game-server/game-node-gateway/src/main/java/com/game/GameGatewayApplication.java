package com.game;

import com.game.comment.config.CommentConfig;
import com.game.message.rpc.GameRpcMessages;
import com.xs.net.message.MessageAble;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Import({CommentConfig.class})
@SpringBootApplication
public class GameGatewayApplication {

    private static final Logger LOG = LoggerFactory.getLogger(GameGatewayApplication.class);

    public static void main(String[] args) {
        // 手动初始化消息注册表，避免依赖 Spring 上下文时序（与 GameLogicApplication 一致）
        MessageAble.initialize(GameRpcMessages.INSTANCE);
        GameRpcMessages.ROUTES.all().forEach(route -> LOG.info(
                "RPC route: number={}, direction={}, role={}, public={}, playerRoute={}, channelRoute={}, transports={}",
                route.rpcNumber(), route.direction(), route.targetRole() == null ? "none" : route.targetRole().getName(),
                route.publicVisible(), route.playerRoutingRequired(), route.channelRoutingRequired(), route.transports()));
        //--add-opens java.base/sun.nio.ch=ALL-UNNAMED
        SpringApplication.run(GameGatewayApplication.class, args);
    }

}
