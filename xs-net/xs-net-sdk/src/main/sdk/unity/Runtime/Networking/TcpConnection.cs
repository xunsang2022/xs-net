using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>
    /// TCP 连接：4 字节大端长度帧 + NetMessage body。
    /// 连接建立后首帧发送 JWT(UTF-8) 完成握手，随后进入游戏消息模式。
    /// </summary>
    public sealed class TcpConnection : NetConnection
    {
        private TcpClient _client;
        private NetworkStream _stream;
        private Thread _readThread;
        private volatile bool _running;

        public TcpConnection(NetworkConfig config, MessageDispatcher dispatcher)
            : base(config, dispatcher)
        {
        }

        public override bool IsConnected => _client != null && _client.Connected && _running;

        public override void Connect(string jwt)
        {
            Disconnect();
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

            // 握手：首帧发 JWT(UTF-8)
            byte[] token = Encoding.UTF8.GetBytes(jwt ?? string.Empty);
            WriteFrame(token);

            _running = true;
            _readThread = new Thread(ReadLoop) { IsBackground = true };
            _readThread.Start();

            RaiseConnected();
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
                WriteFrame(body);
            }
        }

        public override void Disconnect()
        {
            _running = false;
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

        private void WriteFrame(byte[] body)
        {
            byte[] frame = Frame.EncodeTcpFrame(body);
            _stream.Write(frame, 0, frame.Length);
            _stream.Flush();
        }

        private void ReadLoop()
        {
            try
            {
                var header = new byte[4];
                while (_running)
                {
                    ReadExactly(_stream, header, 0, 4);
                    uint len = Frame.ReadUInt32BE(header, 0);
                    if (len == 0 || len > Frame.MaxFrameLength)
                    {
                        throw new IOException("Invalid frame length: " + len);
                    }
                    byte[] body = new byte[len];
                    ReadExactly(_stream, body, 0, (int)len);
                    HandleBody(body);
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
