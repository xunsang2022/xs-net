using System;
using System.Collections.Generic;

namespace Game.Message.Networking
{
    /// <summary>
    /// KCP 之上的一层 XOR 前向纠错（FEC）：发送端把 k 条原始数据报组成一组并附加 m 条冗余报，
    /// 接收端只要收到 k+m 条中的任意 k 条即可在本地还原丢失的数据报，无需等待重传。
    ///
    /// <para><b>线格式（大端）：</b>seq(4) + k(1) + m(1) + idx(1) + len(2) + payload。
    /// idx &lt; k 为数据分片；idx &gt;= k 为冗余分片，其 payload 前缀是 2×k 字节的各数据分片真实长度表，
    /// 其后是按组内最大长度零填充后逐字节异或的结果。</para>
    ///
    /// <para><b>两组约定：</b></para>
    /// <list type="bullet">
    ///   <item>发送端在组满 k 条或调用方显式 <see cref="FlushGroup"/> 时组包发出——不足 k 条时补空分片，
    ///         因此不会为凑组引入额外时延；</item>
    ///   <item>接收端收到数据分片立即交付（顺序交给 KCP 处理），当且仅当整组只缺 1 条时本地 XOR 还原，
    ///         因此正常情况下不增加交付时延。</item>
    /// </list>
    ///
    /// <para>非线程安全：调用方必须与 KCP 状态机处于同一串行域。</para>
    /// </summary>
    public sealed class FecCodec
    {
        /// <summary>头长度：seq(4) + k(1) + m(1) + idx(1) + len(2)。</summary>
        private const int Header = 9;

        private readonly int _k;
        private readonly int _m;
        private readonly Action<byte[]> _output;

        private int _sendSeq;
        private readonly byte[][] _pending;
        private int _pendingCount;
        private int _pendingMaxLen;

        private readonly Dictionary<int, Group> _groups = new Dictionary<int, Group>();

        public FecCodec(int k, int m, Action<byte[]> output)
        {
            _k = Math.Max(1, k);
            _m = Math.Max(1, m);
            _output = output;
            _pending = new byte[_k][];
        }

        /* -------------------- 发送端 -------------------- */

        /// <summary>收集一条原始 KCP 数据报；组满 k 条立即编码发出。</summary>
        public void Encode(byte[] datagram)
        {
            _pending[_pendingCount++] = datagram;
            if (datagram.Length > _pendingMaxLen)
            {
                _pendingMaxLen = datagram.Length;
            }
            if (_pendingCount == _k)
            {
                EmitGroup();
            }
        }

        /// <summary>把当前不足 k 条的组补空分片后发出（调用方在每次 KCP flush 结束后调用）。</summary>
        public void FlushGroup()
        {
            if (_pendingCount > 0)
            {
                EmitGroup();
            }
        }

        private void EmitGroup()
        {
            var count = _pendingCount;
            var lengths = new int[_k];
            var parity = new byte[_pendingMaxLen];
            for (var i = 0; i < _k; i++)
            {
                var shard = i < count ? _pending[i] : Array.Empty<byte>();
                lengths[i] = shard.Length;
                for (var b = 0; b < shard.Length; b++)
                {
                    parity[b] ^= shard[b];
                }
            }
            for (var i = 0; i < count; i++)
            {
                _output(Frame(i, lengths[i], _pending[i]));
            }
            var parityPayload = new byte[2 * _k + _pendingMaxLen];
            for (var i = 0; i < _k; i++)
            {
                parityPayload[i * 2] = (byte)((uint)lengths[i] >> 8);
                parityPayload[i * 2 + 1] = (byte)lengths[i];
            }
            Array.Copy(parity, 0, parityPayload, 2 * _k, parity.Length);
            for (var i = 0; i < _m; i++)
            {
                _output(Frame(_k + i, parityPayload.Length, parityPayload));
            }
            _sendSeq++;
            _pendingCount = 0;
            _pendingMaxLen = 0;
            Array.Clear(_pending, 0, _pending.Length);
        }

        private byte[] Frame(int idx, int len, byte[] payload)
        {
            var framed = new byte[Header + len];
            WriteInt(framed, 0, _sendSeq);
            framed[4] = (byte)_k;
            framed[5] = (byte)_m;
            framed[6] = (byte)idx;
            framed[7] = (byte)((uint)len >> 8);
            framed[8] = (byte)len;
            Array.Copy(payload, 0, framed, Header, Math.Min(len, payload.Length));
            return framed;
        }

        /* -------------------- 接收端 -------------------- */

        /// <summary>解码一条到达的 FEC 数据报：数据分片立即交付；可按长度表判定缺 1 条时立即本地还原后交付。</summary>
        public void Decode(byte[] datagram, Action<byte[]> deliver)
        {
            if (datagram.Length < Header)
            {
                return;
            }
            var seq = ReadInt(datagram, 0);
            var groupK = datagram[4] & 0xFF;
            var groupM = datagram[5] & 0xFF;
            var idx = datagram[6] & 0xFF;
            var len = ((datagram[7] & 0xFF) << 8) | (datagram[8] & 0xFF);
            var total = groupK + groupM;
            if (groupK < 1 || groupM < 1 || idx >= total || Header + len > datagram.Length)
            {
                return;
            }
            if (!_groups.TryGetValue(seq, out var group))
            {
                group = new Group(total);
                _groups[seq] = group;
            }
            if (group.Seen[idx])
            {
                return;
            }
            group.Seen[idx] = true;
            group.Payloads[idx] = CopyOfRange(datagram, Header, Header + len);
            group.Received++;
            if (idx < groupK && len > 0)
            {
                deliver(group.Payloads[idx]);
            }
            if (group.Received == total)
            {
                _groups.Remove(seq);
                return;
            }
            if (Recover(group, groupK, groupM, deliver))
            {
                _groups.Remove(seq);
            }
        }

        /// <summary>
        /// 依据冗余分片内的长度表判断本组是否已可收尾：只缺 1 条非空数据分片时本地 XOR 还原并交付。
        /// </summary>
        /// <returns>本组是否已终结（无需再等待后续分片）。</returns>
        private static bool Recover(Group group, int groupK, int groupM, Action<byte[]> deliver)
        {
            byte[] lengthTable = null;
            for (var i = groupK; i < groupK + groupM; i++)
            {
                if (group.Payloads[i] != null)
                {
                    lengthTable = group.Payloads[i];
                    break;
                }
            }
            if (lengthTable == null)
            {
                // 冗余分片尚未到达：无法判定缺失集合，继续等待
                return false;
            }
            var missing = -1;
            var missingCount = 0;
            var maxLen = 0;
            for (var i = 0; i < groupK; i++)
            {
                var realLen = ((lengthTable[i * 2] & 0xFF) << 8) | (lengthTable[i * 2 + 1] & 0xFF);
                if (realLen > maxLen)
                {
                    maxLen = realLen;
                }
                if (group.Payloads[i] == null)
                {
                    if (realLen == 0)
                    {
                        continue; // 空分片发送端本就不发送，不算缺失
                    }
                    missing = i;
                    missingCount++;
                }
            }
            if (missingCount > 1)
            {
                return false; // 缺失过多，单条 XOR 冗余无法还原
            }
            if (missingCount == 0)
            {
                return true; // 非空分片已全部到达，本组结束
            }
            var recovered = new byte[maxLen];
            for (var i = 0; i < groupK; i++)
            {
                var shard = group.Payloads[i];
                if (shard == null)
                {
                    continue;
                }
                for (var b = 0; b < shard.Length; b++)
                {
                    recovered[b] ^= shard[b];
                }
            }
            for (var b = 0; b < maxLen && 2 * groupK + b < lengthTable.Length; b++)
            {
                recovered[b] ^= lengthTable[2 * groupK + b];
            }
            var realLen2 = ((lengthTable[missing * 2] & 0xFF) << 8) | (lengthTable[missing * 2 + 1] & 0xFF);
            deliver(CopyOfRange(recovered, 0, Math.Min(realLen2, recovered.Length)));
            return true;
        }

        /// <summary>丢弃超时未凑齐的分组，避免长尾泄漏（调用方按维护节拍调用）。</summary>
        public void EvictStale(long nowMs, long maxAgeMs)
        {
            List<int> stale = null;
            foreach (var entry in _groups)
            {
                if (nowMs - entry.Value.CreatedMs > maxAgeMs)
                {
                    (stale ??= new List<int>()).Add(entry.Key);
                }
            }
            if (stale == null)
            {
                return;
            }
            foreach (var key in stale)
            {
                _groups.Remove(key);
            }
        }

        /// <summary>未凑齐分组数，供观测/测试。</summary>
        public int PendingGroups()
        {
            return _groups.Count;
        }

        private sealed class Group
        {
            public readonly bool[] Seen;
            public readonly byte[][] Payloads;
            public readonly long CreatedMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            public int Received;

            public Group(int total)
            {
                Seen = new bool[total];
                Payloads = new byte[total][];
            }
        }

        private static byte[] CopyOfRange(byte[] source, int from, int to)
        {
            var length = to - from;
            var result = new byte[length];
            Array.Copy(source, from, result, 0, length);
            return result;
        }

        private static void WriteInt(byte[] buffer, int offset, int value)
        {
            buffer[offset] = (byte)((uint)value >> 24);
            buffer[offset + 1] = (byte)((uint)value >> 16);
            buffer[offset + 2] = (byte)((uint)value >> 8);
            buffer[offset + 3] = (byte)value;
        }

        private static int ReadInt(byte[] buffer, int offset)
        {
            return ((buffer[offset] & 0xFF) << 24)
                    | ((buffer[offset + 1] & 0xFF) << 16)
                    | ((buffer[offset + 2] & 0xFF) << 8)
                    | (buffer[offset + 3] & 0xFF);
        }
    }
}
