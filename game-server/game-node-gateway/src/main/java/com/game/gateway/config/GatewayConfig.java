package com.game.gateway.config;

import com.game.comment.util.JwtUtil;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Getter
@Configuration
public class GatewayConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpiration;

    @Value("${game.auth.login-base-url:http://127.0.0.1:8081}")
    private String loginBaseUrl;

    @Value("${game.auth.verify-mode:local}")
    private String verifyMode;

    @Value("${game.auth.verify-timeout-ms:2000}")
    private long verifyTimeoutMs;

    @Bean
    public JwtUtil jwtUtil() {
        return new JwtUtil(jwtSecret, jwtExpiration);
    }

    @Bean
    public RestClient loginRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(verifyTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(verifyTimeoutMs));
        return builder.baseUrl(loginBaseUrl).requestFactory(requestFactory).build();
    }
}
