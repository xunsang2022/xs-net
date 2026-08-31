using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>
    /// UDP 连接：基于轻量可靠 UDP 与服务端网关通信。
    /// <para>握手：先发 HELLO(JWT) 建会话，收到 ACK 视为认证完成；随后经 <see cref="ReliableUdp"/>
    /// 可靠收发 DATA；周期发 HEARTBEAT 维持会话，周期重传未确认在途包。</para>
    /// </summary>
    public sealed class UdpConnection : NetConnection
    {
        private UdpClient _client;
        private ReliableUdp _reliable;
        private Thread _receiveThread;
        private volatile bool _running;
        private volatile bool _handshaken;
        private Timer _retryTimer;
        private Timer _heartbeatTimer;

        public UdpConnection(NetworkConfig config, MessageDispatcher dispatcher)
            : base(config, dispatcher)
        {
            _reliable = new ReliableUdp(config.WindowSize)
            {
                SendAction = SendDatagram,
                OnDeliver = OnDeliverPayload
            };
        }

        public override bool IsConnected => _running && _handshaken;

        public override void Connect(string jwt)
        {
            Disconnect();
            var client = new UdpClient(Config.Host, Config.UdpPort);
            _client = client;
            _running = true;
            _handshaken = false;

            _receiveThread = new Thread(ReceiveLoop) { IsBackground = true };
            _receiveThread.Start();

            // 握手：发 HELLO(JWT)
            byte[] token = Encoding.UTF8.GetBytes(jwt ?? string.Empty);
            byte[] hello = Frame.EncodeUdpPacket(Frame.TypeHello, 0, 0, token);
            client.Send(hello, hello.Length);

            _retryTimer = new Timer(_ => _reliable.RetryOutstanding(), null,
                Config.RetryIntervalMs, Config.RetryIntervalMs);
            _heartbeatTimer = new Timer(_ => SendHeartbeat(), null,
                Config.HeartbeatIntervalMs, Config.HeartbeatIntervalMs);
        }

        public override void Send(RpcRoute route, IMessage message)
        {
            Send((int)route, message.ToByteArray());
        }

        public override void Send(int rpcNum, byte[] payload)
        {
            var envelope = new NetMessage { RpcNum = rpcNum, Data = payload };
            _reliable.Send(envelope.ToByteArray());
        }

        public override void Disconnect()
        {
            _running = false;
            _handshaken = false;
            _retryTimer?.Dispose();
            _retryTimer = null;
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
        }

        private void SendDatagram(byte[] datagram)
        {
            if (_client != null)
            {
                _client.Send(datagram, datagram.Length);
            }
        }

        private void SendHeartbeat()
        {
            if (_running)
            {
                SendDatagram(Frame.EncodeUdpPacket(Frame.TypeHeartbeat, 0, _reliable.Contiguous, null));
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
                    if (datagram.Length < Frame.HeaderLength)
                    {
                        continue;
                    }
                    if (!Frame.TryDecodeUdpPacket(datagram, out var packet))
                    {
                        continue;
                    }
                    if (!_handshaken)
                    {
                        // 未认证：仅接受 ACK 作为握手完成信号
                        if (packet.Type == Frame.TypeAck)
                        {
                            _handshaken = true;
                            RaiseConnected();
                        }
                        continue;
                    }
                    if (packet.Type == Frame.TypeData || packet.Type == Frame.TypeAck)
                    {
                        _reliable.OnPacket(packet);
                    }
                    // HEARTBEAT/ERROR 等无需额外处理
                }
            }
            catch (Exception e)
            {
                if (_running)
                {
                    RaiseError("UDP receive error: " + e.Message);
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
            var envelope = NetMessage.ParseFrom(payload);
            Dispatcher.Dispatch((RpcRoute)envelope.RpcNum, envelope.Data);
        }
    }
}
