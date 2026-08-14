package com.game.message.rpc;

import com.xs.net.message.RpcEnvelopeFactory;
import com.xs.net.message.RpcMessageRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GameRpcConfig {

    @Bean
    public RpcMessageRegistry rpcMessageRegistry() {
        return GameRpcMessages.INSTANCE;
    }

    @Bean
    public RpcEnvelopeFactory rpcEnvelopeFactory() {
        return new GameRpcEnvelopeFactory();
    }
}
