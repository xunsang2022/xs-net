package com.game.gateway.kcp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/**
 * KCP 之上的一层 XOR 前向纠错（FEC）：发送端把 k 条原始数据报组成一组并附加 m 条冗余报，
 * 接收端只要收到 k+m 条中的任意 k 条即可在本地还原丢失的数据报，无需等待重传。
 *
 * <p><b>线格式（大端）：</b>seq(4) + k(1) + m(1) + idx(1) + len(2) + payload。
 * idx &lt; k 为数据分片；idx ≥ k 为冗余分片，其 payload 前缀是 2×k 字节的各数据分片真实长度表，
 * 其后是按组内最大长度零填充后逐字节异或的结果。</p>
 *
 * <p><b>两组约定：</b></p>
 * <ul>
 *   <li>发送端在组满 k 条或调用方显式 {@link #flushGroup()} 时组包发出——不足 k 条时补空分片，
 *       因此不会为凑组引入时延；</li>
 *   <li>接收端收到数据分片立即交付（顺序交给 KCP 处理）；一旦冗余分片到达，且按长度表判断
 *       恰好只缺 1 条非空数据分片，即本地 XOR 还原——因此稀疏流量（如游戏握手只有一个包）同样受保护，
 *       不必等 k 条凑满。</li>
 * </ul>
 *
 * <p>非线程安全：调用方必须与 KCP 状态机处于同一串行域（服务端为 {@code synchronized(session)}）。</p>
 */
public final class FecCodec {

    /** 头长度：seq(4) + k(1) + m(1) + idx(1) + len(2)。 */
    static final int HEADER = 9;

    private final int k;
    private final int m;
    private final Consumer<byte[]> out;

    private int sendSeq;
    private final byte[][] pending;
    private int pendingCount;
    private int pendingMaxLen;

    private final Map<Integer, Group> groups = new HashMap<>();

    public FecCodec(int k, int m, Consumer<byte[]> out) {
        this.k = Math.max(1, k);
        this.m = Math.max(1, m);
        this.out = out;
        this.pending = new byte[this.k][];
    }

    /* -------------------- 发送端 -------------------- */

    /** 收集一条原始 KCP 数据报；组满 k 条立即编码发出。 */
    public void encode(byte[] datagram) {
        pending[pendingCount++] = datagram;
        if (datagram.length > pendingMaxLen) {
            pendingMaxLen = datagram.length;
        }
        if (pendingCount == k) {
            emitGroup();
        }
    }

    /** 把当前不足 k 条的组补空分片后发出（调用方在每次 KCP flush 结束后调用）。 */
    public void flushGroup() {
        if (pendingCount > 0) {
            emitGroup();
        }
    }

    private void emitGroup() {
        int count = pendingCount;
        int[] lengths = new int[k];
        byte[] parity = new byte[pendingMaxLen];
        for (int i = 0; i < k; i++) {
            byte[] shard = i < count ? pending[i] : new byte[0];
            lengths[i] = shard.length;
            for (int b = 0; b < shard.length; b++) {
                parity[b] ^= shard[b];
            }
        }
        for (int i = 0; i < count; i++) {
            out.accept(frame(i, lengths[i], pending[i]));
        }
        byte[] parityPayload = new byte[2 * k + pendingMaxLen];
        for (int i = 0; i < k; i++) {
            parityPayload[i * 2] = (byte) (lengths[i] >>> 8);
            parityPayload[i * 2 + 1] = (byte) lengths[i];
        }
        System.arraycopy(parity, 0, parityPayload, 2 * k, parity.length);
        for (int i = 0; i < m; i++) {
            out.accept(frame(k + i, parityPayload.length, parityPayload));
        }
        sendSeq++;
        pendingCount = 0;
        pendingMaxLen = 0;
        Arrays.fill(pending, null);
    }

    private byte[] frame(int idx, int len, byte[] payload) {
        byte[] framed = new byte[HEADER + len];
        writeInt(framed, 0, sendSeq);
        framed[4] = (byte) k;
        framed[5] = (byte) m;
        framed[6] = (byte) idx;
        framed[7] = (byte) (len >>> 8);
        framed[8] = (byte) len;
        System.arraycopy(payload, 0, framed, HEADER, Math.min(len, payload.length));
        return framed;
    }

    /* -------------------- 接收端 -------------------- */

    /** 解码一条到达的 FEC 数据报：数据分片立即交付；可按长度表判定缺 1 条时立即本地还原后交付。 */
    public void decode(byte[] datagram, Consumer<byte[]> deliver) {
        if (datagram.length < HEADER) {
            return;
        }
        int seq = readInt(datagram, 0);
        int groupK = datagram[4] & 0xFF;
        int groupM = datagram[5] & 0xFF;
        int idx = datagram[6] & 0xFF;
        int len = ((datagram[7] & 0xFF) << 8) | (datagram[8] & 0xFF);
        int total = groupK + groupM;
        if (groupK < 1 || groupM < 1 || idx >= total || HEADER + len > datagram.length) {
            return;
        }
        Group group = groups.computeIfAbsent(seq, key -> new Group(total));
        if (group.seen[idx]) {
            return;
        }
        group.seen[idx] = true;
        group.payloads[idx] = Arrays.copyOfRange(datagram, HEADER, HEADER + len);
        group.received++;
        if (idx < groupK && len > 0) {
            deliver.accept(group.payloads[idx]);
        }
        if (group.received == total) {
            groups.remove(seq);
            return;
        }
        if (recover(group, groupK, groupM, deliver)) {
            groups.remove(seq);
        }
    }

    /**
     * 依据冗余分片内的长度表判断本组是否已可收尾：只缺 1 条非空数据分片时本地 XOR 还原并交付。
     *
     * @return 本组是否已终结（无需再等待后续分片）
     */
    private boolean recover(Group group, int groupK, int groupM, Consumer<byte[]> deliver) {
        byte[] lengthTable = null;
        for (int i = groupK; i < groupK + groupM; i++) {
            if (group.payloads[i] != null) {
                lengthTable = group.payloads[i];
                break;
            }
        }
        if (lengthTable == null) {
            // 冗余分片尚未到达：无法判定缺失集合，继续等待
            return false;
        }
        int missing = -1;
        int missingCount = 0;
        int maxLen = 0;
        for (int i = 0; i < groupK; i++) {
            int realLen = ((lengthTable[i * 2] & 0xFF) << 8) | (lengthTable[i * 2 + 1] & 0xFF);
            if (realLen > maxLen) {
                maxLen = realLen;
            }
            if (group.payloads[i] == null) {
                if (realLen == 0) {
                    continue; // 空分片发送端本就不发送，不算缺失
                }
                missing = i;
                missingCount++;
            }
        }
        if (missingCount > 1) {
            return false; // 缺失过多，单条 XOR 冗余无法还原
        }
        if (missingCount == 0) {
            return true; // 非空分片已全部到达，本组结束
        }
        byte[] recovered = new byte[maxLen];
        for (int i = 0; i < groupK; i++) {
            byte[] shard = group.payloads[i];
            if (shard == null) {
                continue;
            }
            for (int b = 0; b < shard.length; b++) {
                recovered[b] ^= shard[b];
            }
        }
        for (int b = 0; b < maxLen && 2 * groupK + b < lengthTable.length; b++) {
            recovered[b] ^= lengthTable[2 * groupK + b];
        }
        int realLen = ((lengthTable[missing * 2] & 0xFF) << 8) | (lengthTable[missing * 2 + 1] & 0xFF);
        deliver.accept(Arrays.copyOf(recovered, Math.min(realLen, recovered.length)));
        return true;
    }

    /** 丢弃超时未凑齐的分组，避免长尾泄漏（调用方按维护节拍调用）。 */
    public void evictStale(long nowMs, long maxAgeMs) {
        Iterator<Map.Entry<Integer, Group>> iterator = groups.entrySet().iterator();
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().getValue().createdMs > maxAgeMs) {
                iterator.remove();
            }
        }
    }

    /** 未凑齐分组数，供观测/测试。 */
    public int pendingGroups() {
        return groups.size();
    }

    /**
     * 判定一条到达的数据报是否为承载 KCP 报文的数据分片，供会话识别使用（FEC 帧头会遮蔽 KCP 的会话号）。
     *
     * @return 数据分片（idx &lt; k 且长度足以容纳一个 KCP 帧头）返回 true；冗余分片或非法帧返回 false
     */
    public static boolean isDataShard(byte[] datagram) {
        if (datagram.length < HEADER + Kcp.OVERHEAD) {
            return false;
        }
        int groupK = datagram[4] & 0xFF;
        int idx = datagram[6] & 0xFF;
        int len = ((datagram[7] & 0xFF) << 8) | (datagram[8] & 0xFF);
        return groupK >= 1 && idx < groupK && len >= Kcp.OVERHEAD && HEADER + len <= datagram.length;
    }

    /**
     * 不解码、不拷贝地读取数据分片内承载的 KCP 会话号。
     * <p>与会话识别同一热路径，故直接在原数据报上按帧头偏移取 4 字节，避免每包一次载荷复制。</p>
     *
     * @return 内部 KCP 帧的会话号；仅当 {@link #isDataShard} 返回 true 时调用
     */
    public static int peekConv(byte[] datagram) {
        return readInt(datagram, HEADER);
    }

    private static final class Group {
        private final boolean[] seen;
        private final byte[][] payloads;
        private final long createdMs = System.currentTimeMillis();
        private int received;

        private Group(int total) {
            this.seen = new boolean[total];
            this.payloads = new byte[total][];
        }
    }

    private static void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >>> 24);
        buffer[offset + 1] = (byte) (value >>> 16);
        buffer[offset + 2] = (byte) (value >>> 8);
        buffer[offset + 3] = (byte) value;
    }

    private static int readInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 24)
                | ((buffer[offset + 1] & 0xFF) << 16)
                | ((buffer[offset + 2] & 0xFF) << 8)
                | (buffer[offset + 3] & 0xFF);
    }
}
