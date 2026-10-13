package com.game.eventsourcing.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// "有界最新值缓冲区"，核心作用是把同一个实体的多次状态提交合并成一条脏记录，并为它设置容量上限，避免内存无限膨胀
public final class DirtyStateBuffer {
    public enum SubmitResult {
        ACCEPTED,
        COALESCED,
        /// 拒收容量
        REJECTED_CAPACITY
    }

    private final int maxRecords;
    private final long maxBytes;
    private final ConcurrentHashMap<RecordKey, DirtyRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();

    public DirtyStateBuffer(int maxRecords, long maxBytes) {
        if (maxRecords <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("DirtyStateBuffer limits must be positive");
        }
        this.maxRecords = maxRecords;
        this.maxBytes = maxBytes;
    }

    public synchronized SubmitResult submit(String entityType, Long entityId, Map<String, String> fields, long version) {
        if (entityType == null || entityId == null || fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("entityType, entityId and fields are required");
        }

        RecordKey key = new RecordKey(entityType, entityId);
        DirtyRecord existing = records.get(key);

        // 分支 1：首次提交
        if (existing == null) {
            DirtyRecord candidate = new DirtyRecord(
                    entityType,
                    entityId,
                    new LinkedHashMap<>(fields),
                    Math.max(0L, version - 1L),
                    version);
            long candidateBytes = estimate(candidate);
            if (records.size() >= maxRecords || bytes.get() + candidateBytes > maxBytes) {
                return SubmitResult.REJECTED_CAPACITY;
            }
            bytes.addAndGet(candidateBytes);
            records.put(key, candidate);
            return SubmitResult.ACCEPTED;
        }

        // 分支 2：合并已有记录
        Map<String, String> merged = new LinkedHashMap<>(existing.fields());
        merged.putAll(fields);
        DirtyRecord candidate = new DirtyRecord(
                entityType,
                entityId,
                merged,
                existing.expectedVersion(),
                Math.max(existing.newVersion(), version));
        // P1: 合并也会改变字节数，必须同样检查容量上限，否则 maxBytes 保证被破坏。
        // delta 可能为负（字段覆盖但无新增），此时不会触发拒绝。
        long delta = estimate(candidate) - estimate(existing);
        if (bytes.get() + delta > maxBytes) {
            return SubmitResult.REJECTED_CAPACITY;
        }
        bytes.addAndGet(delta);
        records.put(key, candidate);
        return SubmitResult.COALESCED;
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

    public synchronized void removeIfVersion(RecordKey key, long version) {
        DirtyRecord current = records.get(key);
        if (current == null || current.version() != version) {
            return;
        }
        bytes.addAndGet(-estimate(current));
        records.remove(key);
    }

    /**
     * 无条件移除某个实体键的脏记录，返回被移除的记录（若不存在返回 null）。
     * 用于 saveNow / 终态回收等"该实体键已无待同步状态"的路径。
     * 与 removeIfVersion 的区别：不检查版本，语义是"放弃该实体键的所有待同步状态"。
     */
    public synchronized DirtyRecord remove(RecordKey key) {
        DirtyRecord removed = records.remove(key);
        if (removed != null) {
            bytes.addAndGet(-estimate(removed));
        }
        return removed;
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized long bytes() {
        return Math.max(0L, bytes.get());
    }

    private long estimate(DirtyRecord record) {
        // Long.BYTES * 2 = expectedVersion + newVersion 两个 long 字段
        long total = Long.BYTES * 2L;
        // entityType 是字符串，entityId 是 Long 对象引用 + long 值
        total += record.entityType() == null ? 0 : record.entityType().length() * 2L;
        total += record.entityId() == null ? 0 : Long.BYTES;
        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            // 32L：Map.Entry 对象的粗略开销（对象头 + 两个引用）
            total += 32L;
            total += entry.getKey() == null ? 0 : entry.getKey().length() * 2L;
            total += entry.getValue() == null ? 0 : entry.getValue().length() * 2L;
        }
        return total;
    }
}