using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>
    /// 标准 KCP 可靠 UDP 连接：与既有 <see cref="UdpConnection"/>（自研 ReliableUdp）并列的可切换传输。
    /// <para>握手：以 KCP 首帧直接发送 JWT(UTF-8) 载荷给服务端认证；当 KCP 交付一条空载荷(len=0) 视为 auth-ok，
    /// 置 <see cref="_handshaken"/> 并 <see cref="NetConnection.RaiseConnected"/>；此后净荷是 NetMessage 字节，
    /// 解析后交给 <see cref="MessageDispatcher"/> 分发。每 tick 调用 <see cref="Kcp.Update"/> 驱动首包发出、ACK 与重传。</para>
    /// </summary>
    public sealed class KcpConnection : NetConnection
    {
        // KCP 会话号（两端一致即可），用一个固定会话号
        private const uint KcpConv = 0x12345678;

        private UdpClient _client;
        private Kcp _kcp;
        private Thread _receiveThread;
        private Timer _tickTimer;
        private readonly object _sync = new();
        private volatile bool _running;
        private volatile bool _handshaken;

        public KcpConnection(NetworkConfig config, MessageDispatcher dispatcher)
            : base(config, dispatcher)
        {
        }

        public override bool IsConnected => _running && _handshaken;

        public override void Connect(string jwt)
        {
            Disconnect();

            var client = new UdpClient(Config.Host, Config.KcpPort);
            _client = client;
            _kcp = new Kcp(KcpConv)
            {
                Output = SendDatagram,
                Deliver = OnDeliverPayload
            };
            _running = true;
            _handshaken = false;

            // 接收线程
            _receiveThread = new Thread(ReceiveLoop) { IsBackground = true };
            _receiveThread.Start();

            // 握手：KCP 首帧直接把 JWT(UTF-8) 作为载荷发出；Send 后立即 Update flush 使首包真正写出
            byte[] token = Encoding.UTF8.GetBytes(jwt ?? string.Empty);
            lock (_sync)
            {
                _kcp.Send(token);
                _kcp.Update(NowMs());
            }

            // 周期 Update：首次 flush 已发出，此后驱动 ACK / 超时重传
            _tickTimer = new Timer(_ => OnTick(), null,
                Config.RetryIntervalMs, Config.RetryIntervalMs);
        }

        public override void Send(RpcRoute route, IMessage message)
        {
            Send((int)route, message.ToByteArray());
        }

        public override void Send(int rpcNum, byte[] payload)
        {
            var envelope = new NetMessage { RpcNum = rpcNum, Data = payload };
            lock (_sync)
            {
                if (_kcp == null) return;
                _kcp.Send(envelope.ToByteArray());
                _kcp.Update(NowMs()); // 立即 flush，让发送更及时
            }
        }

        public override void Disconnect()
        {
            _running = false;
            _handshaken = false;
            _tickTimer?.Dispose();
            _tickTimer = null;
            try
            {
                _client?.Close();
            }
            catch
            {
            }
            _client = null;
            _kcp = null;
        }

        private static uint NowMs() => (uint)Environment.TickCount;

        private void OnTick()
        {
            if (!_running) return;
            lock (_sync)
            {
                _kcp?.Update(NowMs());
            }
        }

        private void SendDatagram(byte[] datagram)
        {
            if (_client != null)
            {
                _client.Send(datagram, datagram.Length);
            }
        }

        private void ReceiveLoop()
        {
            try
            {
                while (_running)
                {
                    IPEndPoint? remote = null;
                    byte[] datagram = _client.Receive(ref remote);
                    lock (_sync)
                    {
                        if (!_running || _kcp == null) continue;
                        _kcp.Input(datagram); // 内部触发 deliver 回调（可能在新线程上下文，先缓存再统一调度）
                    }
                }
            }
            catch (Exception e)
            {
                if (_running)
                {
                    RaiseError("KCP receive error: " + e.Message);
                    RaiseDisconnected();
                }
            }
            finally
            {
                _running = false;
                Disconnect();
            }
        }

        private void OnDeliverPayload(byte[] payload)
        {
            if (!_handshaken)
            {
                // 握手：空载荷 = auth-ok
                if (payload == null || payload.Length == 0)
                {
                    _handshaken = true;
                    RaiseConnected();
                }
                return;
            }
            var envelope = NetMessage.ParseFrom(payload);
            Dispatcher.Dispatch((RpcRoute)envelope.RpcNum, envelope.Data);
        }
    }
}