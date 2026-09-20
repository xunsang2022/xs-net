using System;

namespace Game.Message.Networking
{
    /// <summary>XS packet codec. All integers are big-endian.</summary>
    public static class Frame
    {
        public const byte TypeData = 1;
        public const byte TypeAck = 2;
        public const byte TypeHello = 3;
        public const byte TypeHeartbeat = 4;
        public const byte TypeError = 5;

        public const int OffLength = 0;
        public const int OffPacketId = 4;
        public const int OffSendSeq = 6;
        public const int OffRandom = 10;
        public const int OffHeadCrc = 14;
        public const int OffBodyCrc = 16;
        public const int HeaderLength = 18;
        public const int MaxFrameLength = 65536;

        public struct Packet
        {
            public byte Type;
            public uint Seq;
            public uint Random;
            public byte[] Payload;

            // Compatibility alias for callers that used the old UDP ACK field.
            public uint Ack { get => Seq; set => Seq = value; }
        }

        public static byte[] EncodeUdpPacket(byte packetId, uint sendSeq, uint random, byte[] body)
        {
            return EncodePacket(packetId, sendSeq, random, body);
        }

        public static byte[] EncodeTcpFrame(byte[] body)
        {
            return EncodePacket(TypeData, 0, 0, body);
        }

        public static byte[] EncodePacket(byte packetId, uint sendSeq, uint random, byte[] body)
        {
            body ??= Array.Empty<byte>();
            if (body.Length > MaxFrameLength - HeaderLength)
            {
                throw new ArgumentException("Packet body is too large: " + body.Length);
            }
            byte[] result = new byte[HeaderLength + body.Length];
            WriteUInt32BE(result, OffLength, (uint)body.Length);
            WriteUInt16BE(result, OffPacketId, packetId);
            WriteUInt32BE(result, OffSendSeq, sendSeq);
            WriteUInt32BE(result, OffRandom, random);
            WriteUInt16BE(result, OffHeadCrc, Crc16(result, 0, OffHeadCrc));
            WriteUInt16BE(result, OffBodyCrc, Crc16(body, 0, body.Length));
            if (body.Length > 0) Array.Copy(body, 0, result, HeaderLength, body.Length);
            return result;
        }

        public static bool TryDecodeUdpPacket(byte[] data, out Packet packet)
        {
            return TryDecodePacket(data, out packet);
        }

        public static bool TryDecodePacket(byte[] data, out Packet packet)
        {
            packet = default;
            if (data == null || data.Length < HeaderLength) return false;
            uint length = ReadUInt32BE(data, OffLength);
            if (length > MaxFrameLength - HeaderLength || data.Length != HeaderLength + (int)length) return false;
            if (ReadUInt16BE(data, OffHeadCrc) != Crc16(data, 0, OffHeadCrc)) return false;
            if (ReadUInt16BE(data, OffBodyCrc) != Crc16(data, HeaderLength, (int)length)) return false;
            packet.Type = (byte)ReadUInt16BE(data, OffPacketId);
            packet.Seq = ReadUInt32BE(data, OffSendSeq);
            packet.Random = ReadUInt32BE(data, OffRandom);
            packet.Payload = new byte[length];
            if (length > 0) Array.Copy(data, HeaderLength, packet.Payload, 0, (int)length);
            return true;
        }

        public static ushort Crc16(byte[] data, int offset, int length)
        {
            int crc = 0xffff;
            for (int index = offset; index < offset + length; index++)
            {
                crc ^= (data[index] & 0xff) << 8;
                for (int bit = 0; bit < 8; bit++)
                {
                    crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                    crc &= 0xffff;
                }
            }
            return (ushort)crc;
        }

        public static ushort ReadUInt16BE(byte[] buf, int offset)
        {
            return (ushort)((buf[offset] << 8) | buf[offset + 1]);
        }

        public static void WriteUInt16BE(byte[] buf, int offset, ushort value)
        {
            buf[offset] = (byte)(value >> 8);
            buf[offset + 1] = (byte)value;
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
