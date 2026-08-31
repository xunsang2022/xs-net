package com.game.gateway.management;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GmControllerTest {

    @Test
    void normalizesAndBoundsManagementTextFields() {
        String operator = GmController.sanitizeOperator("  operator" + (char) 1 + "x".repeat(80) + "  ");
        String reason = GmController.sanitizeReason("  reason\n" + "x".repeat(300) + "  ");

        assertEquals(64, operator.length());
        assertEquals(256, reason.length());
        assertEquals("unknown", GmController.sanitizeOperator(String.valueOf((char) 1) + "\n"));
        assertEquals("unspecified", GmController.sanitizeReason(null));
    }
}
