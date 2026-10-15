package com.game.gateway.kcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEC 编解码单元测试：整组收发、缺 1 条本地还原、丢冗余无法还原、重复分片忽略、超时分组清理。
 */
class FecCodecTest {

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 发送端编码 k 条数据报，收集实际写出的所有帧。 */
    private static List<byte[]> encodeAll(int k, int m, byte[]... datagrams) {
        List<byte[]> frames = new ArrayList<>();
        FecCodec sender = new FecCodec(k, m, frames::add);
        for (byte[] datagram : datagrams) {
            sender.encode(datagram);
        }
        sender.flushGroup();
        return frames;
    }

    /** 把若干帧（跳过 dropIndexes 指定的下标）灌入接收端，返回按序交付的载荷。 */
    private static List<byte[]> receive(List<byte[]> frames, int... dropIndexes) {
        List<byte[]> delivered = new ArrayList<>();
        FecCodec receiver = new FecCodec(2, 1, frame -> { });
        for (int i = 0; i < frames.size(); i++) {
            boolean dropped = false;
            for (int dropIndex : dropIndexes) {
                dropped |= i == dropIndex;
            }
            if (!dropped) {
                receiver.decode(frames.get(i), delivered::add);
            }
        }
        return delivered;
    }

    @Test
    void fullGroup_deliversAllInOrder() {
        List<byte[]> frames = encodeAll(2, 1, bytes("alpha"), bytes("beta"));
        assertEquals(3, frames.size(), "k=2,m=1 应写出 2 条数据 + 1 条冗余");

        List<byte[]> delivered = receive(frames, -1);
        assertEquals(2, delivered.size());
        assertArrayEquals(bytes("alpha"), delivered.get(0));
        assertArrayEquals(bytes("beta"), delivered.get(1));
    }

    @Test
    void oneDataShardLost_recoveredByParity() {
        List<byte[]> frames = encodeAll(2, 1, bytes("alpha"), bytes("beta"));

        List<byte[]> delivered = receive(frames, 1); // 丢掉 idx=1 的数据分片
        assertEquals(2, delivered.size(), "缺 1 条数据分片应由冗余本地还原");
        assertArrayEquals(bytes("alpha"), delivered.get(0));
        assertArrayEquals(bytes("beta"), delivered.get(1));
    }

    @Test
    void parityLostWithDataShard_cannotRecover() {
        List<byte[]> frames = encodeAll(2, 1, bytes("alpha"), bytes("beta"));

        // 丢失一条数据分片 + 冗余分片：仅剩的 alpha 立刻交付，beta 无法本地还原，交由 ARQ 重传
        List<byte[]> delivered = receive(frames, 1, 2);
        assertEquals(1, delivered.size(), "缺失分片与冗余同时丢失时不可还原");
        assertArrayEquals(bytes("alpha"), delivered.get(0));
    }

    @Test
    void duplicateShard_ignored() {
        List<byte[]> frames = encodeAll(2, 1, bytes("alpha"), bytes("beta"));

        List<byte[]> delivered = new ArrayList<>();
        FecCodec receiver = new FecCodec(2, 1, frame -> { });
        receiver.decode(frames.get(0), delivered::add);
        receiver.decode(frames.get(0), delivered::add); // 重复投递同一分片
        receiver.decode(frames.get(1), delivered::add);
        receiver.decode(frames.get(2), delivered::add);
        assertEquals(2, delivered.size(), "重复分片不应被重复交付");
    }

    @Test
    void partialGroupOnlyDeliversArrivedShards() {
        List<byte[]> frames = encodeAll(4, 1, bytes("one"), bytes("two")); // 不足一组，flushGroup 补空分片
        assertEquals(3, frames.size(), "2 条数据 + 1 条冗余");

        List<byte[]> delivered = receive(frames, -1);
        assertEquals(2, delivered.size(), "空分片不应产生交付");
        assertArrayEquals(bytes("one"), delivered.get(0));
        assertArrayEquals(bytes("two"), delivered.get(1));
    }

    @Test
    void singleDataShardGroup_recoveredFromParityWhenDataLost() {
        // 稀疏流量场景：整组只有 1 条数据分片，数据分片丢失时仍可由冗余本地还原（游戏握手的关键场景）
        List<byte[]> frames = encodeAll(2, 1, bytes("only-one"));
        assertEquals(2, frames.size(), "1 条数据 + 1 条冗余");

        List<byte[]> delivered = receive(frames, 0); // 丢掉唯一的数据分片
        assertEquals(1, delivered.size(), "单包分组丢包应由冗余还原，无需等重传");
        assertArrayEquals(bytes("only-one"), delivered.get(0));
    }

    @Test
    void evictStale_dropsUnfinishedGroups() {
        FecCodec receiver = new FecCodec(4, 1, frame -> { });
        List<byte[]> frames = encodeAll(4, 1, bytes("s1"), bytes("s2"), bytes("s3"), bytes("s4"));
        receiver.decode(frames.get(0), payload -> { }); // 只到达 1 条数据，冗余未到，无从判定缺失集合
        assertEquals(1, receiver.pendingGroups(), "未凑齐的组应停留在缓冲中");
        receiver.evictStale(System.currentTimeMillis() + 6000, 5000);
        assertEquals(0, receiver.pendingGroups(), "超时未凑齐的组应被清理");
    }

    @Test
    void oversizedDatagram_survivesRoundTrip() {
        byte[] big = new byte[1400];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        List<byte[]> frames = encodeAll(2, 1, bytes("head"), big);

        List<byte[]> delivered = receive(frames, 1); // 丢掉大报，靠冗余还原
        assertEquals(2, delivered.size());
        assertArrayEquals(bytes("head"), delivered.get(0));
        assertArrayEquals(big, delivered.get(1));
    }

    @Test
    void peekConv_locatesInnerSegmentWithoutCopy() {
        byte[] first = new byte[24];
        first[0] = 0x12;
        first[1] = 0x34;
        first[2] = 0x56;
        first[3] = 0x78;
        byte[] second = new byte[24];
        second[0] = (byte) 0x80; // 随机 conv 可为负数，识别不能以符号判非法
        second[3] = 0x01;

        List<byte[]> frames = encodeAll(2, 1, first, second);
        assertTrue(FecCodec.isDataShard(frames.get(0)), "数据分片应被识别");
        assertEquals(0x12345678, FecCodec.peekConv(frames.get(0)));
        assertTrue(FecCodec.isDataShard(frames.get(1)));
        assertEquals(0x80000001, FecCodec.peekConv(frames.get(1)), "负数会话号必须原样读出");

        assertFalse(FecCodec.isDataShard(frames.get(2)), "冗余分片不是数据分片，须返回 false");
        assertFalse(FecCodec.isDataShard(new byte[4]), "短于帧头 + KCP 帧头的报不是数据分片");
        assertFalse(FecCodec.isDataShard(bytes("alpha")), "载荷短于 KCP 帧头的报不是数据分片");
    }

    @Test
    void malformedDatagramIgnored() {
        List<byte[]> delivered = new ArrayList<>();
        FecCodec receiver = new FecCodec(2, 1, frame -> { });
        receiver.decode(new byte[4], delivered::add);   // 短于头长
        receiver.decode(new byte[9], delivered::add);   // 头声称 len=0 但 k=0 非法
        assertTrue(delivered.isEmpty());
    }
}
