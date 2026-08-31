package com.game.logic.management;

public interface ArthasTraceClient {

    ArthasTraceExecution trace(ProtocolTraceTarget target, int timeoutMs) throws Exception;
}
