package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSupportTest {

    private static final String SECRET = "xs-net-gateway-auth-support-test-secret";
    private static final long EXPIRATION_MILLIS = 60_000L;
    private static final String CHANNEL_ID = "auth-support-test-channel";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, EXPIRATION_MILLIS);

    @AfterEach
    void cleanRegistry() {
        ChannelRegistry.INSTANCE.remove(CHANNEL_ID);
    }

    @Test
    void resolveReturnsChannelIdForValidToken() {
        byte[] token = jwtUtil.generateToken("player-1", CHANNEL_ID).getBytes(StandardCharsets.UTF_8);

        AuthSupport.Result result = AuthSupport.resolve(jwtUtil, token);

        assertTrue(result.success());
        assertNull(result.failure());
        assertEquals(CHANNEL_ID, result.channelId());
    }

    @Test
    void resolveReturnsInvalidTokenFailureForTamperedToken() {
        byte[] token = (jwtUtil.generateToken("player-1", CHANNEL_ID) + "x").getBytes(StandardCharsets.UTF_8);

        AuthSupport.Result result = AuthSupport.resolve(jwtUtil, token);

        assertFalse(result.success());
        assertEquals(AuthSupport.Failure.INVALID_TOKEN, result.failure());
        assertNull(result.channelId());
    }

    @Test
    void resolveReturnsInvalidTokenFailureWhenValidatorRejectsToken() {
        JwtUtil rejecting = mock(JwtUtil.class);
        when(rejecting.validateToken(anyString())).thenReturn(null);

        AuthSupport.Result result = AuthSupport.resolve(rejecting, "whatever".getBytes(StandardCharsets.UTF_8));

        assertFalse(result.success());
        assertEquals(AuthSupport.Failure.INVALID_TOKEN, result.failure());
        assertNull(result.channelId());
    }

    @Test
    void resolveReturnsMissingChannelIdFailureWhenClaimIsBlank() {
        byte[] token = jwtUtil.generateToken("player-1", "   ").getBytes(StandardCharsets.UTF_8);

        AuthSupport.Result result = AuthSupport.resolve(jwtUtil, token);

        assertFalse(result.success());
        assertEquals(AuthSupport.Failure.MISSING_CHANNEL_ID, result.failure());
        assertNull(result.channelId());
    }

    @Test
    void admitReconnectAllowsWhenNoExistingConnection() {
        assertTrue(AuthSupport.admitReconnect(CHANNEL_ID, 7L, 1));
    }

    @Test
    void admitReconnectReplacesSameLogicalConnection() {
        ClientEndpoint existing = activeEndpoint(7L);
        ChannelRegistry.INSTANCE.register(CHANNEL_ID, existing);

        assertTrue(AuthSupport.admitReconnect(CHANNEL_ID, 7L, 1));

        assertNull(ChannelRegistry.INSTANCE.get(CHANNEL_ID));
        verify(existing).close();
    }

    @Test
    void admitReconnectRejectsDuplicateActiveConnectionWithDifferentRandom() {
        ClientEndpoint existing = activeEndpoint(7L);
        ChannelRegistry.INSTANCE.register(CHANNEL_ID, existing);

        assertFalse(AuthSupport.admitReconnect(CHANNEL_ID, 8L, 1));

        assertSame(existing, ChannelRegistry.INSTANCE.get(CHANNEL_ID));
        verify(existing, never()).close();
    }

    @Test
    void admitReconnectAllowsWhenExistingConnectionIsInactive() {
        ClientEndpoint existing = mock(ClientEndpoint.class);
        when(existing.isActive()).thenReturn(false);
        ChannelRegistry.INSTANCE.register(CHANNEL_ID, existing);

        assertTrue(AuthSupport.admitReconnect(CHANNEL_ID, 8L, 1));

        assertSame(existing, ChannelRegistry.INSTANCE.get(CHANNEL_ID));
    }

    @Test
    void admitReconnectBypassesDuplicateCheckWhenLimitDisabled() {
        ClientEndpoint existing = activeEndpoint(7L);
        ChannelRegistry.INSTANCE.register(CHANNEL_ID, existing);

        assertTrue(AuthSupport.admitReconnect(CHANNEL_ID, 8L, 0));

        assertSame(existing, ChannelRegistry.INSTANCE.get(CHANNEL_ID));
        verify(existing, never()).close();
    }

    @Test
    void metricCountersDoNotThrow() {
        assertDoesNotThrow(() -> AuthSupport.countSuccess(AuthSupport.TCP_METRICS));
        assertDoesNotThrow(() -> AuthSupport.countFailure(AuthSupport.TCP_METRICS));
        assertDoesNotThrow(() -> AuthSupport.countC2S(AuthSupport.TCP_METRICS));
    }

    @Test
    void markLoginIngressRecordsTimestamp() {
        AuthSupport.markLoginIngress(CHANNEL_ID);

        assertNotNull(ChannelRegistry.INSTANCE.takeLoginIngress(CHANNEL_ID));
    }

    private static ClientEndpoint activeEndpoint(long connectionRandom) {
        ClientEndpoint endpoint = mock(ClientEndpoint.class);
        when(endpoint.isActive()).thenReturn(true);
        when(endpoint.connectionRandom()).thenReturn(connectionRandom);
        return endpoint;
    }
}
