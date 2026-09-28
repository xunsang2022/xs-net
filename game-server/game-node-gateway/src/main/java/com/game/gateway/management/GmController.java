package com.game.gateway.management;

import com.game.message.actor.logic.GMMessage;
import com.game.message.management.GmKickPlayerRequest;
import com.xs.kernel.akka.AkkaKit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/gm")
@RequiredArgsConstructor
public final class GmController {

    private static final String TOKEN_HEADER = "X-GM-Token";
    private static final String GM_ENTITY_ID = "management";
    private static final int MAX_REASON_LENGTH = 256;
    private static final int MAX_OPERATOR_LENGTH = 64;

    /** 由 Spring 注入的管理令牌校验器，不能为空。 */
    @NonNull
    private final ManagementTokenVerifier tokenVerifier;

    @PostMapping("/kick")
    public ResponseEntity<Map<String, String>> kick(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) KickRequest request) {
        if (!tokenVerifier.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!tokenVerifier.matches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request == null || request.playerId() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "playerId must be positive"));
        }

        String reason = sanitizeReason(request.reason());
        String operator = sanitizeOperator(request.operator());
        String requestId = UUID.randomUUID().toString();
        try {
            AkkaKit.tell(GMMessage.class, new GmKickPlayerRequest(
                    requestId,
                    request.playerId(),
                    operator,
                    reason,
                    System.currentTimeMillis()), GM_ENTITY_ID);
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "management cluster is unavailable"));
        }
        return ResponseEntity.accepted().body(Map.of("requestId", requestId, "status", "accepted"));
    }

    static String sanitizeReason(String reason) {
        return sanitize(reason, "unspecified", MAX_REASON_LENGTH);
    }

    static String sanitizeOperator(String operator) {
        return sanitize(operator, "unknown", MAX_OPERATOR_LENGTH);
    }

    private static String sanitize(String value, String fallback, int maxLength) {
        if (value == null) {
            return fallback;
        }
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(normalized::appendCodePoint);
        String result = normalized.toString().strip();
        if (result.isEmpty()) {
            return fallback;
        }
        return result.length() > maxLength ? result.substring(0, maxLength) : result;
    }

    public record KickRequest(long playerId, String operator, String reason) {
    }
}
