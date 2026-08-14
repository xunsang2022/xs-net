package com.game;

import com.game.comment.config.CommentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Import({CommentConfig.class})
@SpringBootApplication
public class GameGatewayApplication {

    public static void main(String[] args) {
        //--add-opens java.base/sun.nio.ch=ALL-UNNAMED
        SpringApplication.run(GameGatewayApplication.class, args);
    }

}
