using System;
using System.IO;

namespace Game.Message.Networking
{
    /// <summary>
    /// 消息信封，对应服务端 <c>net_message.proto</c> 的 <c>NetMessage</c>。
    /// <para>手动实现 protobuf wire 编解码（仅此固定 6 字段），避免 SDK 额外生成/依赖该信封类型。</para>
    /// <code>
    ///   int32  rpc_num    = 1;
    ///   bytes  data       = 2;
    ///   int32  error_code = 3;
    ///   int32  session_id = 4;
    ///   int32  user_id    = 5;
    ///   string user_ip    = 6;
    /// </code>
    /// </summary>
    public sealed class NetMessage
    {
        private const int WireVarint = 0;
        private const int WireLengthDelimited = 2;

        public int RpcNum { get; set; }
        public byte[] Data { get; set; }
        public int ErrorCode { get; set; }
        public int SessionId { get; set; }
        public int UserId { get; set; }
        public string UserIp { get; set; }

        public byte[] ToByteArray()
        {
            using var ms = new MemoryStream();
            WriteInt32Field(ms, 1, RpcNum);
            if (Data != null && Data.Length > 0)
            {
                WriteTag(ms, 2, WireLengthDelimited);
                WriteVarint(ms, (uint)Data.Length);
                ms.Write(Data, 0, Data.Length);
            }
            if (ErrorCode != 0)
            {
                WriteInt32Field(ms, 3, ErrorCode);
            }
            if (SessionId != 0)
            {
                WriteInt32Field(ms, 4, SessionId);
            }
            if (UserId != 0)
            {
                WriteInt32Field(ms, 5, UserId);
            }
            if (!string.IsNullOrEmpty(UserIp))
            {
                byte[] bytes = System.Text.Encoding.UTF8.GetBytes(UserIp);
                WriteTag(ms, 6, WireLengthDelimited);
                WriteVarint(ms, (uint)bytes.Length);
                ms.Write(bytes, 0, bytes.Length);
            }
            return ms.ToArray();
        }

        public static NetMessage ParseFrom(byte[] data)
        {
            var msg = new NetMessage();
            int index = 0;
            while (index < data.Length)
            {
                ulong tag = ReadVarint(data, ref index);
                int field = (int)(tag >> 3);
                int wire = (int)(tag & 0x7);
                switch (field)
                {
                    case 1:
                        msg.RpcNum = (int)ReadVarint(data, ref index);
                        break;
                    case 2:
                    {
                        int len = (int)ReadVarint(data, ref index);
                        msg.Data = new byte[len];
                        Array.Copy(data, index, msg.Data, 0, len);
                        index += len;
                        break;
                    }
                    case 3:
                        msg.ErrorCode = (int)ReadVarint(data, ref index);
                        break;
                    case 4:
                        msg.SessionId = (int)ReadVarint(data, ref index);
                        break;
                    case 5:
                        msg.UserId = (int)ReadVarint(data, ref index);
                        break;
                    case 6:
                    {
                        int len = (int)ReadVarint(data, ref index);
                        msg.UserIp = System.Text.Encoding.UTF8.GetString(data, index, len);
                        index += len;
                        break;
                    }
                    default:
                        SkipField(data, ref index, wire);
                        break;
                }
            }
            return msg;
        }

        private static void WriteInt32Field(MemoryStream ms, int field, int value)
        {
            WriteTag(ms, field, WireVarint);
            // int32 负数按 64 位符号扩展编码，保证与标准 protobuf 一致
            WriteVarint(ms, value < 0 ? (ulong)(long)value : (ulong)value);
        }

        private static void WriteTag(MemoryStream ms, int field, int wireType)
        {
            WriteVarint(ms, ((ulong)(uint)field << 3) | (uint)wireType);
        }

        private static void WriteVarint(MemoryStream ms, ulong value)
        {
            while (value >= 0x80)
            {
                ms.WriteByte((byte)(value | 0x80));
                value >>= 7;
            }
            ms.WriteByte((byte)value);
        }

        private static ulong ReadVarint(byte[] data, ref int index)
        {
            ulong result = 0;
            int shift = 0;
            while (true)
            {
                if (index >= data.Length)
                {
                    throw new InvalidDataException("Truncated varint");
                }
                byte b = data[index++];
                result |= (ulong)(b & 0x7F) << shift;
                if ((b & 0x80) == 0)
                {
                    break;
                }
                shift += 7;
                if (shift >= 64)
                {
                    throw new InvalidDataException("Malformed varint");
                }
            }
            return result;
        }

        private static void SkipField(byte[] data, ref int index, int wire)
        {
            switch (wire)
            {
                case 0: // varint
                    ReadVarint(data, ref index);
                    break;
                case 2: // length-delimited
                {
                    int len = (int)ReadVarint(data, ref index);
                    index += len;
                    break;
                }
                case 1: // 64-bit
                    index += 8;
                    break;
                case 5: // 32-bit
                    index += 4;
                    break;
                default:
                    throw new InvalidDataException("Unsupported wire type: " + wire);
            }
        }
    }
}
