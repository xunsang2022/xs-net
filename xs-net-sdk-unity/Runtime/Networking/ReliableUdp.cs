using System;
using System.Collections.Generic;

namespace Game.Message.Networking
{
    /// <summary>
    /// 客户端侧轻量可靠 UDP 层：序列号 / 在途窗口 / 累积 ACK / 超时重传 / 乱序缓存。
    /// <para>与服务端 <c>UdpReliableLayer</c> 逻辑对称。通过 <see cref="SendAction"/> 回调把编码后的
    /// datagram 交回连接层真正写出；<see cref="OnDeliver"/> 按序交付 DATA payload。</para>
    /// </summary>
    public sealed class ReliableUdp
    {
        /// <summary>写出完整 datagram 字节（由连接层实现）。</summary>
        public Action<byte[]> SendAction { get; set; }

        /// <summary>按序交付的 payload（NetMessage 字节）。</summary>
        public Action<byte[]> OnDeliver { get; set; }

        private readonly int _window;
        private readonly SortedDictionary<uint, byte[]> _inFlight = new();
        private readonly SortedDictionary<uint, byte[]> _reorder = new();

        private uint _sendSeq = 1;
        private uint _nextRecvSeq = 1;
        private uint _highestAck;

        public ReliableUdp(int window)
        {
            _window = Math.Max(1, window);
        }

        /// <summary>发送一条业务消息：分配 seq 入在途窗口并组 DATA 包发出。</summary>
        public void Send(byte[] payload)
        {
            if (_inFlight.Count >= _window)
            {
                // 窗口满：丢弃最旧腾出空间
                _inFlight.Remove(FirstKey(_inFlight));
            }
            uint seq = _sendSeq++;
            _inFlight[seq] = payload;
            Emit(Frame.TypeData, seq, _highestAck, payload);
        }

        /// <summary>处理收到的对端包：DATA 按序交付并回 ACK；ACK 移除已确认在途。</summary>
        public void OnPacket(Frame.Packet packet)
        {
            if (packet.Ack > 0)
            {
                RemoveAcked(packet.Ack);
            }
            if (packet.Type == Frame.TypeData)
            {
                HandleData(packet.Seq, packet.Payload);
                Emit(Frame.TypeAck, 0, _highestAck, null);
            }
        }

        private void HandleData(uint seq, byte[] payload)
        {
            if (seq < _nextRecvSeq)
            {
                // 重复包：丢弃
                return;
            }
            if (seq == _nextRecvSeq)
            {
                Deliver(seq, payload);
                // 补发乱序缓存中可连序的部分
                while (_reorder.Count > 0)
                {
                    uint key = FirstKey(_reorder);
                    if (key != _nextRecvSeq)
                    {
                        break;
                    }
                    byte[] value = _reorder[key];
                    _reorder.Remove(key);
                    Deliver(key, value);
                }
            }
            else
            {
                // 乱序：缓存待前序到达后补序
                if (!_reorder.ContainsKey(seq))
                {
                    _reorder[seq] = payload;
                }
                while (_reorder.Count > _window * 2)
                {
                    _reorder.Remove(LastKey(_reorder));
                }
            }
        }

        private void Deliver(uint seq, byte[] payload)
        {
            _nextRecvSeq = seq + 1;
            if (seq > _highestAck)
            {
                _highestAck = seq;
            }
            OnDeliver?.Invoke(payload);
        }

        /// <summary>当前已连续收到的最大 seq（用于累积 ACK）。</summary>
        public uint Contiguous => _highestAck;

        /// <summary>重传所有未确认在途包（由定时器调用）。</summary>
        public void RetryOutstanding()
        {
            foreach (var kv in _inFlight)
            {
                Emit(Frame.TypeData, kv.Key, _highestAck, kv.Value);
            }
        }

        /// <summary>当前在途未确认数量。</summary>
        public int Outstanding => _inFlight.Count;

        private void RemoveAcked(uint ack)
        {
            var toRemove = new List<uint>();
            foreach (uint key in _inFlight.Keys)
            {
                if (key <= ack)
                {
                    toRemove.Add(key);
                }
                else
                {
                    break;
                }
            }
            foreach (uint key in toRemove)
            {
                _inFlight.Remove(key);
            }
        }

        private void Emit(byte type, uint seq, uint ack, byte[] payload)
        {
            SendAction?.Invoke(Frame.EncodeUdpPacket(type, seq, ack, payload));
        }

        private static uint FirstKey(SortedDictionary<uint, byte[]> dict)
        {
            using var e = dict.GetEnumerator();
            e.MoveNext();
            return e.Current.Key;
        }

        private static uint LastKey(SortedDictionary<uint, byte[]> dict)
        {
            using var e = dict.GetEnumerator();
            uint last = 0;
            while (e.MoveNext())
            {
                last = e.Current.Key;
            }
            return last;
        }
    }
}
