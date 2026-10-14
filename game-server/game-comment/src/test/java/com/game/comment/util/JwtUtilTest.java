package com.game.comment.util;

import io.jsonwebtoken.Claims;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JwtUtilTest {

    private static final String SECRET = "xs-net-comment-unit-test-secret-32bytes!";
    private static final long EXPIRATION_MILLIS = 60_000L;

    @Test
    public void constructorRejectsNullSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil(null, EXPIRATION_MILLIS));
    }

    @Test
    public void constructorRejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("", EXPIRATION_MILLIS));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("     ", EXPIRATION_MILLIS));
    }

    @Test
    public void constructorRejectsSecretShorterThan32Bytes() {
        String shortSecret = "0123456789012345678901234567890"; // 31 bytes
        assertEquals(31, shortSecret.getBytes(StandardCharsets.UTF_8).length);

        assertThrows(IllegalArgumentException.class, () -> new JwtUtil(shortSecret, EXPIRATION_MILLIS));
    }

    @Test
    public void constructorAcceptsSecretOfExactly32Bytes() {
        String secret = "01234567890123456789012345678901"; // 32 bytes

        JwtUtil jwtUtil = new JwtUtil(secret, EXPIRATION_MILLIS);

        assertEquals(EXPIRATION_MILLIS, jwtUtil.getExpirationMillis());
    }

    @Test
    public void generateThenValidateRoundTrip() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, EXPIRATION_MILLIS);

        String token = jwtUtil.generateToken("player-1", "channel-1");
        Claims claims = jwtUtil.validateToken(token);

        assertNotNull(claims);
        assertEquals("player-1", jwtUtil.getPlayerId(claims));
        assertEquals("channel-1", jwtUtil.getChannelId(claims));
        assertNotNull(claims.getIssuedAt());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    public void validateTokenReturnsNullForTamperedToken() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, EXPIRATION_MILLIS);
        String token = jwtUtil.generateToken("player-1", "channel-1");

        assertNull(jwtUtil.validateToken(token + "x"));
    }

    @Test
    public void validateTokenReturnsNullForTokenSignedWithAnotherSecret() {
        JwtUtil signer = new JwtUtil("another-secret-key-that-is-long-enough-32bytes", EXPIRATION_MILLIS);
        JwtUtil verifier = new JwtUtil(SECRET, EXPIRATION_MILLIS);

        assertNull(verifier.validateToken(signer.generateToken("player-1", "channel-1")));
    }

    @Test
    public void validateTokenReturnsNullForExpiredToken() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, -1_000L);

        assertNull(jwtUtil.validateToken(jwtUtil.generateToken("player-1", "channel-1")));
    }
}
