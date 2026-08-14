package com.game;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DbSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbSyncApplication.class, args);
    }
}
