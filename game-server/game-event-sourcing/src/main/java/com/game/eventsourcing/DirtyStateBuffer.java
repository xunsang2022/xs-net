package com.game.eventsourcing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded latest-value buffer for player state persistence. */
public final class DirtyStateBuffer {
    public enum SubmitResult {
        ACCEPTED,
        COALESCED,
        REJECTED_CAPACITY
    }

    private final int maxRecords;
    private final long maxBytes;
    private final ConcurrentHashMap<Long, DirtyRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();

    public DirtyStateBuffer(int maxRecords, long maxBytes) {
        if (maxRecords <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("DirtyStateBuffer limits must be positive");
        }
        this.maxRecords = maxRecords;
        this.maxBytes = maxBytes;
    }

    public synchronized SubmitResult submit(Long playerId, Map<String, String> fields, long version) {
        if (playerId == null || fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("playerId and fields are required");
        }
        final SubmitResult[] result = new SubmitResult[1];
        records.compute(playerId, (key, existing) -> {
            if (existing == null) {
                DirtyRecord candidate = new DirtyRecord(
                        playerId,
                        new LinkedHashMap<>(fields),
                        Math.max(0L, version - 1L),
                        version);
                long candidateBytes = estimate(candidate);
                if (records.size() >= maxRecords || bytes.get() + candidateBytes > maxBytes) {
                    result[0] = SubmitResult.REJECTED_CAPACITY;
                    return null;
                }
                bytes.addAndGet(candidateBytes);
                result[0] = SubmitResult.ACCEPTED;
                return candidate;
            }
            Map<String, String> merged = new LinkedHashMap<>(existing.fields());
            merged.putAll(fields);
            DirtyRecord candidate = new DirtyRecord(
                    playerId,
                    merged,
                    existing.expectedVersion(),
                    Math.max(existing.newVersion(), version));
            bytes.addAndGet(estimate(candidate) - estimate(existing));
            result[0] = SubmitResult.COALESCED;
            return candidate;
        });
        return result[0] == null ? SubmitResult.REJECTED_CAPACITY : result[0];
    }

    public synchronized List<DirtyRecord> snapshot(int limit) {
        List<DirtyRecord> snapshot = new ArrayList<>();
        for (DirtyRecord record : records.values()) {
            if (snapshot.size() >= limit) {
                break;
            }
            snapshot.add(record);
        }
        return snapshot;
    }

    public synchronized void removeIfVersion(Long playerId, long version) {
        records.computeIfPresent(playerId, (key, current) -> {
            if (current.version() != version) {
                return current;
            }
            bytes.addAndGet(-estimate(current));
            return null;
        });
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized long bytes() {
        return Math.max(0L, bytes.get());
    }

    public long maxBytes() {
        return maxBytes;
    }

    private long estimate(DirtyRecord record) {
        long total = Long.BYTES * 2L;
        total += record.playerId() == null ? 0 : Long.BYTES;
        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            total += 32L;
            total += entry.getKey() == null ? 0 : entry.getKey().length() * 2L;
            total += entry.getValue() == null ? 0 : entry.getValue().length() * 2L;
        }
        return total;
    }
}
