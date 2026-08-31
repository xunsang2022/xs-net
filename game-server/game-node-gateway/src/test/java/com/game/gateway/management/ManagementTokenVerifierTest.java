package com.game.gateway.management;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementTokenVerifierTest {

    @Test
    void disabledWhenTokenIsBlank() {
        ManagementTokenVerifier verifier = new ManagementTokenVerifier("");

        assertFalse(verifier.isEnabled());
        assertFalse(verifier.matches("anything"));
    }

    @Test
    void acceptsOnlyTheConfiguredToken() {
        ManagementTokenVerifier verifier = new ManagementTokenVerifier("test-token");

        assertTrue(verifier.isEnabled());
        assertTrue(verifier.matches("test-token"));
        assertFalse(verifier.matches("wrong-token"));
        assertFalse(verifier.matches(null));
    }
}
