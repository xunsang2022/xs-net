using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>
    /// TCP 连接：XS packet 头 + NetMessage body。
    /// </summary>
    public sealed class TcpConnection : NetConnection
    {
        private TcpClient _client;
        private NetworkStream _stream;
        private Thread _readThread;
        private volatile bool _running;
        private volatile bool _handshaken;
        private readonly uint _connectionRandom;
        private uint _sendSeq = 1;
        private readonly ManualResetEventSlim _handshakeEvent = new(false);

        public TcpConnection(NetworkConfig config, MessageDispatcher dispatcher)
            : base(config, dispatcher)
        {
            _connectionRandom = config.ConnectionRandom != 0
                ? config.ConnectionRandom
                : (uint)Random.Shared.NextInt64(1, uint.MaxValue);
        }

        public override bool IsConnected => _client != null && _client.Connected && _running;

        public override void Connect(string jwt)
        {
            Disconnect();
            _handshakeEvent.Reset();
            var client = new TcpClient();
            var connect = client.BeginConnect(Config.Host, Config.TcpPort, null, null);
            if (!connect.AsyncWaitHandle.WaitOne(Config.ConnectTimeoutMs))
            {
                client.Close();
                throw new TimeoutException("TCP connect timeout");
            }
            client.EndConnect(connect);
            _client = client;
            _stream = client.GetStream();
            _stream.ReadTimeout = Config.ReadTimeoutMs;

            // 握手：首帧发 HELLO(JWT)
            byte[] token = Encoding.UTF8.GetBytes(jwt ?? string.Empty);
            WritePacket(Frame.EncodePacket(Frame.TypeHello, 0, _connectionRandom, token));

            _running = true;
            _readThread = new Thread(ReadLoop) { IsBackground = true };
            _readThread.Start();
            if (!_handshakeEvent.Wait(Config.ConnectTimeoutMs))
            {
                Disconnect();
                throw new TimeoutException("TCP handshake timeout");
            }
        }

        public override void Send(RpcRoute route, IMessage message)
        {
            Send((int)route, message.ToByteArray());
        }

        public override void Send(int rpcNum, byte[] payload)
        {
            var envelope = new NetMessage { RpcNum = rpcNum, Data = payload };
            byte[] body = envelope.ToByteArray();
            lock (this)
            {
                if (!IsConnected) throw new InvalidOperationException("TCP connection is not authenticated");
                WritePacket(Frame.EncodePacket(Frame.TypeData, _sendSeq++, _connectionRandom, body));
            }
        }

        public override void Disconnect()
        {
            _running = false;
            _handshaken = false;
            _handshakeEvent.Reset();
            try
            {
                _stream?.Close();
            }
            catch
            {
            }
            try
            {
                _client?.Close();
            }
            catch
            {
            }
            _stream = null;
            _client = null;
        }

        private void WritePacket(byte[] frame)
        {
            _stream.Write(frame, 0, frame.Length);
            _stream.Flush();
        }

        private void ReadLoop()
        {
            try
            {
                var header = new byte[Frame.HeaderLength];
                while (_running)
                {
                    ReadExactly(_stream, header, 0, Frame.HeaderLength);
                    uint len = Frame.ReadUInt32BE(header, 0);
                    if (len > Frame.MaxFrameLength - Frame.HeaderLength)
                    {
                        throw new IOException("Invalid frame length: " + len);
                    }
                    byte[] body = new byte[len];
                    ReadExactly(_stream, body, 0, (int)len);
                    byte[] packetBytes = new byte[Frame.HeaderLength + body.Length];
                    Array.Copy(header, packetBytes, Frame.HeaderLength);
                    Array.Copy(body, 0, packetBytes, Frame.HeaderLength, body.Length);
                    if (!Frame.TryDecodePacket(packetBytes, out var packet) || packet.Random != _connectionRandom)
                    {
                        throw new IOException("Invalid XS packet");
                    }
                    if (packet.Type == Frame.TypeAck && !_handshaken)
                    {
                        _handshaken = true;
                        _handshakeEvent.Set();
                        RaiseConnected();
                    }
                    else if (packet.Type == Frame.TypeData)
                    {
                        WritePacket(Frame.EncodePacket(Frame.TypeAck, packet.Seq, _connectionRandom, null));
                        HandleBody(packet.Payload);
                    }
                }
            }
            catch (Exception e)
            {
                if (_running)
                {
                    RaiseError("TCP read error: " + e.Message);
                    RaiseDisconnected();
                }
            }
            finally
            {
                _running = false;
                Disconnect();
            }
        }

        private void HandleBody(byte[] body)
        {
            var envelope = NetMessage.ParseFrom(body);
            Dispatcher.Dispatch((RpcRoute)envelope.RpcNum, envelope.Data);
        }

        private static void ReadExactly(Stream stream, byte[] buffer, int offset, int count)
        {
            int read = 0;
            while (read < count)
            {
                int n = stream.Read(buffer, offset + read, count - read);
                if (n <= 0)
                {
                    throw new EndOfStreamException("Connection closed");
                }
                read += n;
            }
        }
    }
}
