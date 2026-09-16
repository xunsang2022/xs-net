package com.xs.net.transport;

import java.util.concurrent.atomic.AtomicInteger;

/** Non-blocking admission control suitable for Netty event-loop threads. */
public final class IngressAdmissionController {
    private final int maxInFlight;
    private final AtomicInteger inFlight = new AtomicInteger();

    public IngressAdmissionController(int maxInFlight) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
    }

    public boolean tryAcquire() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int maxInFlight() {
        return maxInFlight;
    }
}
