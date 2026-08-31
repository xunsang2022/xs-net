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
        Assert.Equal(7u, packet.Ack);
        Assert.Equal(payload, packet.Payload);
        Assert.Equal(Frame.HeaderLength + payload.Length, datagram.Length);
    }

    [Fact]
    public void UdpPacket_EmptyPayload_IsValid()
    {
        byte[] datagram = Frame.EncodeUdpPacket(Frame.TypeAck, 0, 9, null);
        Assert.Equal(Frame.HeaderLength, datagram.Length);
        Assert.True(Frame.TryDecodeUdpPacket(datagram, out var packet));
        Assert.Equal(Frame.TypeAck, packet.Type);
        Assert.Empty(packet.Payload);
    }

    [Fact]
    public void UdpPacket_BadMagic_IsRejected()
    {
        byte[] datagram = Frame.EncodeUdpPacket(Frame.TypeData, 1, 0, null);
        datagram[0] = 0x00; // 破坏 magic
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

        Assert.Equal(7, frame.Length);
        Assert.Equal(0, frame[0]); // 大端：最高字节在最前
        Assert.Equal(0, frame[1]);
        Assert.Equal(0, frame[2]);
        Assert.Equal(3, frame[3]);
        Assert.Equal(body, new ArraySegment<byte>(frame, 4, 3));
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
