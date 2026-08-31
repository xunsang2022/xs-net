package com.game.logic.management;

public record ProtocolTraceTarget(
        int requestedProtocolNumber,
        int effectiveProtocolNumber,
        Class<?> messageType,
        Class<?> actorType,
        String handlerMethod
) {

    public String command() {
        return "trace " + actorType.getName() + ' ' + handlerMethod + " -n 1";
    }
}
