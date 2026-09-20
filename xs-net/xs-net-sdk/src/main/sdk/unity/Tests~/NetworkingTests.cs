using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Game.Message.Networking;
using Xunit;

namespace SdkTests;

public class FrameTests
{
    [Fact]
    public void UdpPacket_EncodeDecode_RoundTrip()
    {
        var payload = Encoding.UTF8.GetBytes("hello");
        byte[] datagram = Frame.EncodeUdpPacket(Frame.TypeData, 42, 7, payload);

        Assert.True(Frame.TryDecodeUdpPacket(datagram, out var packet));
        Assert.Equal(Frame.TypeData, packet.Type);
        Assert.Equal(42u, packet.Seq);
        Assert.Equal(7u, packet.Random);
        Assert.Equal(payload, packet.Payload);
        Assert.Equal(Frame.HeaderLength + payload.Length, datagram.Length);
    }

    [Fact]
    public void UdpPacket_EmptyPayload_IsValid()
    {
        byte[] datagram = Frame.EncodeUdpPacket(Frame.TypeAck, 9, 7, null);
        Assert.Equal(Frame.HeaderLength, datagram.Length);
        Assert.True(Frame.TryDecodeUdpPacket(datagram, out var packet));
        Assert.Equal(Frame.TypeAck, packet.Type);
        Assert.Empty(packet.Payload);
    }

    [Fact]
    public void UdpPacket_BadHeaderCrc_IsRejected()
    {
        byte[] datagram = Frame.EncodeUdpPacket(Frame.TypeData, 1, 0, null);
        datagram[6] ^= 0x01; // 破坏 sendSeq，但不更新 headCRC
        Assert.False(Frame.TryDecodeUdpPacket(datagram, out _));
    }

    [Fact]
    public void UdpPacket_TooShort_IsRejected()
    {
        Assert.False(Frame.TryDecodeUdpPacket(new byte[10], out _));
    }

    [Fact]
    public void TcpFrame_LengthPrefix_IsBigEndian()
    {
        var body = new byte[] { 0x01, 0x02, 0x03 };
        byte[] frame = Frame.EncodeTcpFrame(body);

        Assert.Equal(21, frame.Length);
        Assert.Equal(0, frame[0]); // 大端：最高字节在最前
        Assert.Equal(0, frame[1]);
        Assert.Equal(0, frame[2]);
        Assert.Equal(3, frame[3]);
        Assert.Equal(body, new ArraySegment<byte>(frame, Frame.HeaderLength, 3));
    }
}

public class NetMessageTests
{
    [Fact]
    public void RoundTrip_PreservesFields()
    {
        var msg = new NetMessage
        {
            RpcNum = 1000,
            Data = Encoding.UTF8.GetBytes("payload"),
            ErrorCode = 0,
            SessionId = 7,
            UserId = 999,
            UserIp = "127.0.0.1"
        };

        byte[] bytes = msg.ToByteArray();
        var decoded = NetMessage.ParseFrom(bytes);

        Assert.Equal(1000, decoded.RpcNum);
        Assert.Equal(msg.Data, decoded.Data);
        Assert.Equal(7, decoded.SessionId);
        Assert.Equal(999, decoded.UserId);
        Assert.Equal("127.0.0.1", decoded.UserIp);
    }

    [Fact]
    public void EmptyData_DefaultsToZero()
    {
        var msg = new NetMessage { RpcNum = 1002 };
        byte[] bytes = msg.ToByteArray();
        var decoded = NetMessage.ParseFrom(bytes);
        Assert.Equal(1002, decoded.RpcNum);
        Assert.Null(decoded.Data);
    }
}

public class ReliableUdpTests
{
    private static ReliableUdp NewLayer(int window, List<byte[]> delivered, List<Frame.Packet> sent)
    {
        return new ReliableUdp(window)
        {
            SendAction = d =>
            {
                Assert.True(Frame.TryDecodeUdpPacket(d, out var p));
                sent.Add(p);
            },
            OnDeliver = p => delivered.Add(p)
        };
    }

    [Fact]
    public void Data_InOrder_DeliveredOnce()
    {
        var delivered = new List<byte[]>();
        var sent = new List<Frame.Packet>();
        var layer = NewLayer(32, delivered, sent);

        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 1, Payload = A("a") });
        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 2, Payload = A("b") });
        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 3, Payload = A("c") });

        Assert.Equal(new[] { "a", "b", "c" }, ToStrings(delivered));
        // 每次收到 DATA 回一个 ACK，ack 为当前连续最大 seq
        Assert.Equal(3, sent.Count);
    }

    [Fact]
    public void Data_OutOfOrder_BufferedThenFlushed()
    {
        var delivered = new List<byte[]>();
        var sent = new List<Frame.Packet>();
        var layer = NewLayer(32, delivered, sent);

        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 2, Payload = A("b") });
        Assert.Empty(delivered); // 乱序，未交付

        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 3, Payload = A("c") });
        Assert.Empty(delivered);

        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 1, Payload = A("a") });
        Assert.Equal(new[] { "a", "b", "c" }, ToStrings(delivered));
    }

    [Fact]
    public void DuplicateData_Ignored()
    {
        var delivered = new List<byte[]>();
        var sent = new List<Frame.Packet>();
        var layer = NewLayer(32, delivered, sent);

        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 1, Payload = A("a") });
        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 1, Payload = A("a") });
        layer.OnPacket(new Frame.Packet { Type = Frame.TypeData, Seq = 2, Payload = A("b") });

        Assert.Equal(new[] { "a", "b" }, ToStrings(delivered));
    }

    [Fact]
    public void Send_TracksInFlight_AndAckClears()
    {
        var delivered = new List<byte[]>();
        var sent = new List<Frame.Packet>();
        var layer = NewLayer(32, delivered, sent);

        layer.Send(A("x"));
        layer.Send(A("y"));
        Assert.Equal(2, layer.Outstanding);

        // 对端累积确认 seq=2
        layer.OnPacket(new Frame.Packet { Type = Frame.TypeAck, Ack = 2 });
        Assert.Equal(0, layer.Outstanding);
    }

    [Fact]
    public void RetryOutstanding_ResendsAllInFlight()
    {
        var delivered = new List<byte[]>();
        var sent = new List<Frame.Packet>();
        var layer = NewLayer(32, delivered, sent);

        layer.Send(A("x"));
        layer.Send(A("y"));
        int before = sent.Count;
        layer.RetryOutstanding();
        Assert.Equal(before + 2, sent.Count);
    }

    [Fact]
    public async Task ConcurrentSendReceiveAndRetry_DoNotModifyCollectionsDuringIteration()
    {
        var errors = new ConcurrentQueue<Exception>();
        var layer = new ReliableUdp(32)
        {
            SendAction = _ => { },
            OnDeliver = _ => { }
        };

        var tasks = Enumerable.Range(0, 1000).Select(_ => Task.Run(() =>
        {
            try
            {
                layer.Send(A("payload"));
                layer.RetryOutstanding();
                layer.OnPacket(new Frame.Packet { Type = Frame.TypeAck, Ack = uint.MaxValue });
            }
            catch (Exception exception)
            {
                errors.Enqueue(exception);
            }
        }));

        await Task.WhenAll(tasks);

        Assert.Empty(errors);
    }

    private static byte[] A(string s) => Encoding.UTF8.GetBytes(s);

    private static string[] ToStrings(List<byte[]> list)
        => list.ConvertAll(b => Encoding.UTF8.GetString(b)).ToArray();
}

public class KcpTests
{
    // ---- KCP 线格式手工构造（与 Kcp.cs 小端一致，仅用于确定性注入）----
    // 包结构：conv(4) + cmd(1) + frg(1) + wnd(2) + ts(4) + sn(4) + una(4) + len(4) + data

    private static byte[] BuildPush(uint conv, byte frg, ushort wnd, uint ts, uint sn, uint una, byte[] data)
    {
        var buf = new byte[24 + data.Length];
        Write32(buf, 0, conv);
        buf[4] = Kcp.CmdPush;
        buf[5] = frg;
        Write16(buf, 6, wnd);
        Write32(buf, 8, ts);
        Write32(buf, 12, sn);
        Write32(buf, 16, una);
        Write32(buf, 20, (uint)data.Length);
        Array.Copy(data, 0, buf, 24, data.Length);
        return buf;
    }

    private static byte[] BuildAck(uint conv, ushort wnd, uint ts, uint sn, uint una)
    {
        var buf = new byte[24];
        Write32(buf, 0, conv);
        buf[4] = Kcp.CmdAck;
        buf[5] = 0;
        Write16(buf, 6, wnd);
        Write32(buf, 8, ts);
        Write32(buf, 12, sn);
        Write32(buf, 16, una);
        Write32(buf, 20, 0);
        return buf;
    }

    private static void Write16(byte[] b, int off, int v)
    {
        b[off] = (byte)((v >> 8) & 0xff);
        b[off + 1] = (byte)(v & 0xff);
    }

    private static void Write32(byte[] b, int off, uint v)
    {
        b[off] = (byte)((v >> 24) & 0xff);
        b[off + 1] = (byte)((v >> 16) & 0xff);
        b[off + 2] = (byte)((v >> 8) & 0xff);
        b[off + 3] = (byte)(v & 0xff);
    }

    // ---- 单测 ----

    [Fact]
    public void Send_Simple_FlushesOneDatagram()
    {
        var kcp = new Kcp(1);
        var outbound = new List<byte[]>();
        kcp.Output = d => outbound.Add(d);

        int sent = kcp.Send(Encoding.UTF8.GetBytes("hello"));

        Assert.Equal(5, sent);
        uint now = 1000;
        kcp.Update(now);

        Assert.Single(outbound);
        // 首段写入 snd_buf 并分配 sn=0
        Assert.Single(kcp.SndBuf);
        Assert.Equal(0u, kcp.SndBuf[0].sn);
    }

    [Fact]
    public void Data_InOrder_Delivered()
    {
        var kcp = new Kcp(1);
        var delivered = new List<byte[]>();
        kcp.Deliver = m => delivered.Add(m);

        kcp.Input(BuildPush(1, 0, 32, 0, 0, 0, A("a")));
        kcp.Input(BuildPush(1, 0, 32, 0, 1, 0, A("b")));

        Assert.Equal(new[] { "a", "b" }, ToStrings(delivered));
        Assert.Equal(2u, kcp.RcvNxt);
    }

    [Fact]
    public void Data_OutOfOrder_ReorderedBeforeDelivery()
    {
        var kcp = new Kcp(1);
        var delivered = new List<byte[]>();
        kcp.Deliver = m => delivered.Add(m);

        // 先到 sn=1，再到 sn=0：KCP 需在 rcv_buf 中重排后按序交付
        kcp.Input(BuildPush(1, 0, 32, 0, 1, 0, A("b")));
        Assert.Empty(delivered);

        kcp.Input(BuildPush(1, 0, 32, 0, 0, 0, A("a")));

        Assert.Equal(new[] { "a", "b" }, ToStrings(delivered));
        Assert.Equal(2u, kcp.RcvNxt);
    }

    [Fact]
    public void Fragmented_LargeMessage_ReassembledOnce()
    {
        var peer = new Kcp(1);
        var peerDelivered = new List<byte[]>();
        peer.Deliver = m => peerDelivered.Add(m);

        // 一条 3 段消息（mss=40 → 40/40/16），frg：sn0 剩 2、sn1 剩 1、sn2 最后(0)。
        // 乱序注入：sn2 先到、sn0 次之、sn1 最后，验证 rcv_buf 重排 + frg 分片合并。
        peer.Input(BuildPush(1, 0, 32, 0, 2, 0, ArrayPad(new byte[16], (byte)'Z')));
        Assert.Empty(peerDelivered); // 不连续

        peer.Input(BuildPush(1, 2, 32, 0, 0, 0, A("AAAA000000000000000000000000000000000000".Substring(0, 40))));
        Assert.Empty(peerDelivered); // 缺少 sn1

        peer.Input(BuildPush(1, 1, 32, 0, 1, 0, A("BBBB111111111111111111111111111111111111".Substring(0, 40))));

        Assert.Single(peerDelivered);
        Assert.Equal(40 * 2 + 16, peerDelivered[0].Length);
        Assert.Equal("AAAA000000000000000000000000000000000000" +
                     "BBBB111111111111111111111111111111111111" +
                     "ZZZZZZZZZZZZZZZZ",
                     Encoding.UTF8.GetString(peerDelivered[0]));
        Assert.Equal(3u, peer.RcvNxt);
    }

    [Fact]
    public void WrapAround_SequenceNumbers_ContinueAfterUintMax()
    {
        // 把 rcv_nxt 置于回绕边界：0xFFFFFFFF → 0 → 1
        var kcp = new Kcp(1);
        var delivered = new List<byte[]>();
        kcp.Deliver = m => delivered.Add(m);
        kcp.RcvNxt = uint.MaxValue;

        kcp.Input(BuildPush(1, 0, 32, 0, uint.MaxValue, 0, A("wrap")));
        Assert.Equal(new[] { "wrap" }, ToStrings(delivered)); // RcvNxt 溢出到 0

        // 回绕后继续正常接收 sn=0,1
        kcp.Input(BuildPush(1, 0, 32, 0, 0, 0, A("zero")));
        kcp.Input(BuildPush(1, 0, 32, 0, 1, 0, A("one")));
        Assert.Equal(new[] { "wrap", "zero", "one" }, ToStrings(delivered));
        Assert.Equal(2u, kcp.RcvNxt); // 0x00..→0→1→2
    }

    [Fact]
    public void Retransmission_OnTimeout_ReSendsSameSn()
    {
        var kcp = new Kcp(1);
        kcp.SetInterval(100);
        var outbound = new List<byte[]>();
        kcp.Output = d => outbound.Add(d);

        kcp.Send(A("x"));
        kcp.Update(1000); // 首包发出，重传时间 resendts = 1000 + rto(200) + 回退(200) = 1400
        Assert.Single(outbound);
        Assert.Single(kcp.SndBuf);
        Assert.Equal(0u, kcp.SndBuf[0].sn);

        kcp.Update(1399); // 未到 resendts，不重传
        Assert.Single(outbound);

        kcp.Update(1400); // 到达 resendts → 超时重传，snd_buf 唯一段重复发出
        Assert.Equal(2, outbound.Count);
        Assert.Single(kcp.SndBuf);
        Assert.Equal(0u, kcp.SndBuf[0].sn); // 重传段与首段同一 sn
    }

    [Fact]
    public void Rto_Adaptive_UpdatesOnAck()
    {
        var kcp = new Kcp(1);

        // 模拟已发送序列号前进到 5（便于 ParseAck 不触发 SndUna 边界干扰）
        kcp.SndNxt = 5;
        // 收到一个 ts=800 的 ACK，当前时钟 1000 → rtt=200
        kcp.Current = 1000;
        kcp.Input(BuildAck(1, 32, 800, 4, 0));
        Assert.Equal(200, kcp.RxSrtt);
        Assert.True(kcp.RxRto >= kcp.RxMinrto && kcp.RxRto <= Kcp.RtoMax);

        // 第二次 ACK rtt=260，srtt 平滑收敛到 200*7/8 + 260/8 ≈ 207
        kcp.Current = 1060;
        kcp.Input(BuildAck(1, 32, 800, 4, 0));
        Assert.InRange(kcp.RxSrtt, 200, 222);
        Assert.True(kcp.RxRttval >= 0);
    }

    [Fact]
    public void Ack_Received_ClearsSndBuf()
    {
        var kcp = new Kcp(1);
        var outbound = new List<byte[]>();
        kcp.Output = d => outbound.Add(d);

        kcp.Send(A("payload"));
        kcp.Update(1000); // sn=0 进入 snd_buf 并发出
        Assert.Single(kcp.SndBuf);
        Assert.Equal(0u, kcp.SndUna);

        // 收到对端对 sn=0 的 ACK（una 前进到 1）
        kcp.Current = 1100;
        kcp.Input(BuildAck(1, 32, 900, 0, 1));

        Assert.Empty(kcp.SndBuf); // 已确认，移除
        Assert.Equal(1u, kcp.SndUna);
    }

    private static byte[] A(string s) => Encoding.UTF8.GetBytes(s);

    private static byte[] ArrayPad(byte[] data, byte fill)
    {
        for (int i = 0; i < data.Length; i++) data[i] = fill;
        return data;
    }

    private static string[] ToStrings(List<byte[]> list)
        => list.ConvertAll(b => Encoding.UTF8.GetString(b)).ToArray();
}
