using System;

namespace Game.Message.Networking
{
    /// <summary>
    /// 帧协议常量与编解码（大端序）。
    /// <para>UDP 数据报：固定 15 字节头 + payload，一个大 datagram 一个包：</para>
    /// <code>
    ///   偏移  长度  字段        说明
    ///   0     2    magic      0x58 0x53 ("XS")
    ///   2     1    type       1=DATA 2=ACK 3=HELLO 4=HEARTBEAT 5=ERROR
    ///   3     4    seq        发送方递增序列号（DATA 携带）
    ///   7     4    ack        累积确认：对端已收到的最大连续 seq
    ///   11    4    payloadLen payload 字节数
    ///   15    -    payload    DATA=NetMessage 字节 / HELLO=JWT(UTF-8)
    /// </code>
    /// <para>TCP 帧：4 字节大端长度 + body（body 为 NetMessage 字节）。</para>
    /// </summary>
    public static class Frame
    {
        // ---- UDP 包类型 ----
        public const byte TypeData = 1;
        public const byte TypeAck = 2;
        public const byte TypeHello = 3;
        public const byte TypeHeartbeat = 4;
        public const byte TypeError = 5;

        // ---- UDP 头偏移 ----
        public const int OffMagic = 0;
        public const int OffType = 2;
        public const int OffSeq = 3;
        public const int OffAck = 7;
        public const int OffLen = 11;
        public const int HeaderLength = 15;

        private const byte Magic0 = 0x58;
        private const byte Magic1 = 0x53;

        /// <summary>单帧/单包最大长度（与服务端 max-frame-length 对齐）。</summary>
        public const int MaxFrameLength = 65536;

        /// <summary>一个解析后的 UDP 包。</summary>
        public struct Packet
        {
            public byte Type;
            public uint Seq;
            public uint Ack;
            public byte[] Payload;
        }

        /// <summary>构造一个 UDP 数据报字节。</summary>
        public static byte[] EncodeUdpPacket(byte type, uint seq, uint ack, byte[] payload)
        {
            int len = payload?.Length ?? 0;
            byte[] buf = new byte[HeaderLength + len];
            buf[OffMagic] = Magic0;
            buf[OffMagic + 1] = Magic1;
            buf[OffType] = type;
            WriteUInt32BE(buf, OffSeq, seq);
            WriteUInt32BE(buf, OffAck, ack);
            WriteUInt32BE(buf, OffLen, (uint)len);
            if (len > 0)
            {
                Array.Copy(payload, 0, buf, HeaderLength, len);
            }
            return buf;
        }

        /// <summary>解析 UDP 数据报，非法返回 false。</summary>
        public static bool TryDecodeUdpPacket(byte[] data, out Packet packet)
        {
            packet = default;
            if (data == null || data.Length < HeaderLength)
            {
                return false;
            }
            if (data[OffMagic] != Magic0 || data[OffMagic + 1] != Magic1)
            {
                return false;
            }
            packet.Type = data[OffType];
            packet.Seq = ReadUInt32BE(data, OffSeq);
            packet.Ack = ReadUInt32BE(data, OffAck);
            uint len = ReadUInt32BE(data, OffLen);
            if (len > MaxFrameLength - HeaderLength || data.Length < HeaderLength + (int)len)
            {
                return false;
            }
            packet.Payload = new byte[len];
            Array.Copy(data, HeaderLength, packet.Payload, 0, (int)len);
            return true;
        }

        /// <summary>TCP 帧：4 字节大端长度 + body。</summary>
        public static byte[] EncodeTcpFrame(byte[] body)
        {
            if (body == null || body.Length == 0 || body.Length > MaxFrameLength)
            {
                throw new ArgumentException("Invalid frame body length: " + (body?.Length ?? 0));
            }
            byte[] buf = new byte[4 + body.Length];
            WriteUInt32BE(buf, 0, (uint)body.Length);
            Array.Copy(body, 0, buf, 4, body.Length);
            return buf;
        }

        public static uint ReadUInt32BE(byte[] buf, int offset)
        {
            return ((uint)buf[offset] << 24)
                 | ((uint)buf[offset + 1] << 16)
                 | ((uint)buf[offset + 2] << 8)
                 | buf[offset + 3];
        }

        public static void WriteUInt32BE(byte[] buf, int offset, uint value)
        {
            buf[offset] = (byte)(value >> 24);
            buf[offset + 1] = (byte)(value >> 16);
            buf[offset + 2] = (byte)(value >> 8);
            buf[offset + 3] = (byte)value;
        }
    }
}
