package com.game.gateway.management;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public final class ManagementTokenVerifier {

    private final byte[] expectedToken;

    public ManagementTokenVerifier(@Value("${game.management.token:}") String token) {
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return expectedToken.length > 0;
    }

    public boolean matches(String providedToken) {
        if (!isEnabled() || providedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken, providedToken.getBytes(StandardCharsets.UTF_8));
    }
}
