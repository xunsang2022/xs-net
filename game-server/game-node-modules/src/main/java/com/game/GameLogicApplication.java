package com.game;

import com.game.logic.config.LogicNodeRuntime;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Spring Boot entry point for the game logic node. */
@SpringBootApplication
public final class GameLogicApplication {

    private GameLogicApplication() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(GameLogicApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            context.getBean(LogicNodeRuntime.class).awaitTermination();
        }
    }
}
