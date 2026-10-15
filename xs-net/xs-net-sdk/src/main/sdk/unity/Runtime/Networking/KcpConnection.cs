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
        // KCP 会话号：每条连接独立随机生成，服务端据此识别同一源地址上的新连接
        private uint _conv;

        private UdpClient _client;
        private Kcp _kcp;
        private FecCodec _fec;
        private Thread _receiveThread;
        private Timer _tickTimer;
        private Timer _heartbeatTimer;
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
            // 每条连接使用独立随机会话号：服务端凭它区分同一源地址上的新旧连接
            _conv = (uint)Random.Shared.NextInt64(1, uint.MaxValue);
            _kcp = new Kcp(_conv)
            {
                Output = SendDatagram,
                Deliver = OnDeliverPayload
            };
            // FEC：按配置启用；Output 仍指向 SendDatagram（KCP 输出入口），由其分派到编码器或直发
            _fec = Config.FecK > 0 ? new FecCodec(Config.FecK, Config.FecM, SendRaw) : null;
            // 低延迟：nodelay=1、节拍跟随重传间隔、fastresend=2、关闭拥塞窗口（与网关侧一致）
            _kcp.NoDelay(1, Config.RetryIntervalMs, 2, 1);
            _running = true;
            _handshaken = false;

            // 接收线程
            _receiveThread = new Thread(ReceiveLoop) { IsBackground = true };
            _receiveThread.Start();

            // 握手：KCP 首帧直接把 JWT(UTF-8) 作为载荷发出；Send 后立即 Flush 使首包真正写出
            // （不能依赖 Update 的首次调用：Current 初值为 0 时 Update 会跳过 flush，首包将延迟一个节拍）
            byte[] token = Encoding.UTF8.GetBytes(jwt ?? string.Empty);
            lock (_sync)
            {
                _kcp.Send(token);
                _kcp.Update(NowMs());
                _kcp.Flush();
                _fec?.FlushGroup();
            }

            // 周期 Update：首次 flush 已发出，此后驱动 ACK / 超时重传
            _tickTimer = new Timer(_ => OnTick(), null,
                Config.RetryIntervalMs, Config.RetryIntervalMs);
            // 周期保活：KCP 空闲时不产生任何报文，若服务端按会话空闲超时清理会丢失后续消息
            _heartbeatTimer = new Timer(_ => OnHeartbeat(), null,
                Config.HeartbeatIntervalMs, Config.HeartbeatIntervalMs);
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
                _kcp.Update(NowMs());
                _kcp.Flush(); // 忽略 interval 门控，立即把请求写出
                _fec?.FlushGroup();
            }
        }

        public override void Disconnect()
        {
            _running = false;
            _handshaken = false;
            _tickTimer?.Dispose();
            _tickTimer = null;
            _heartbeatTimer?.Dispose();
            _heartbeatTimer = null;
            try
            {
                _client?.Close();
            }
            catch
            {
            }
            _client = null;
            _kcp = null;
            _fec = null;
        }

        private static uint NowMs() => (uint)Environment.TickCount;

        private void OnTick()
        {
            if (!_running) return;
            lock (_sync)
            {
                _kcp?.Update(NowMs());
                _fec?.FlushGroup();
            }
        }

        /// <summary>周期保活：发送 KCP 窗口探测帧（WASK），刷新服务端会话活跃时间，避免空闲会话被清理。</summary>
        private void OnHeartbeat()
        {
            if (!_running) return;
            lock (_sync)
            {
                if (_kcp == null) return;
                _kcp.Probe |= Kcp.AskSend;
                _kcp.Update(NowMs());
                _kcp.Flush();
                _fec?.FlushGroup();
            }
        }

        /// <summary>KCP 输出入口：启用 FEC 时交由编码器组包，否则直接发送。</summary>
        private void SendDatagram(byte[] datagram)
        {
            if (_fec == null)
            {
                SendRaw(datagram);
            }
            else
            {
                _fec.Encode(datagram);
            }
        }

        /// <summary>底层原始数据报发送（FEC 编码后的分片，或未启用 FEC 时的原始 KCP 报文）。</summary>
        private void SendRaw(byte[] datagram)
        {
            // Disconnect 与定时器回调存在竞态：socket 可能已被关闭，忽略此时的发送
            var client = _client;
            if (client == null)
            {
                return;
            }
            try
            {
                client.Send(datagram, datagram.Length);
            }
            catch (ObjectDisposedException)
            {
            }
            catch (SocketException)
            {
                // 本机端口耗尽等发送失败：定时器路径不应因此终止进程，交由 KCP 重传
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
                        if (_fec == null)
                        {
                            _kcp.Input(datagram); // 内部触发 deliver 回调（可能在新线程上下文，先缓存再统一调度）
                        }
                        else
                        {
                            _fec.Decode(datagram, shard => _kcp.Input(shard));
                        }
                        _fec?.FlushGroup();
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