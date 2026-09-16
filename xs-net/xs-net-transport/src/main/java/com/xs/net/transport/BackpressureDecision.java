package com.xs.net.transport;

public enum BackpressureDecision {
    ACCEPTED,
    COALESCED,
    REJECTED_CAPACITY,
    REJECTED_EXPIRED
}
