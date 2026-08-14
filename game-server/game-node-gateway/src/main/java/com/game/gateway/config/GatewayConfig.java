package com.game.gateway.config;

import com.game.comment.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpiration;

    @Bean
    public JwtUtil jwtUtil() {
        return new JwtUtil(jwtSecret, jwtExpiration);
    }
}
