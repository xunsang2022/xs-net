using System;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>
    /// 网络客户端门面：按 <see cref="NetworkConfig.UseUdp"/> 选择 TCP 或 UDP 连接，
    /// 封装连接、JWT 认证、消息发送与上层事件。使用 <see cref="Dispatcher"/> 注册消息处理器。
    /// </summary>
    public sealed class GameNetworkClient : IDisposable
    {
        private readonly NetworkConfig _config;
        private NetConnection _connection;

        public MessageDispatcher Dispatcher { get; } = new MessageDispatcher();

        public event Action OnConnected;
        public event Action OnDisconnected;
        public event Action<string> OnError;

        public GameNetworkClient(NetworkConfig config)
        {
            _config = config ?? throw new ArgumentNullException(nameof(config));
        }

        public bool IsConnected => _connection?.IsConnected ?? false;

        public void Connect(string jwt)
        {
            Disconnect();
            NetConnection connection = _config.UseUdp
                ? new UdpConnection(_config, Dispatcher)
                : new TcpConnection(_config, Dispatcher);
            connection.OnConnected += () => OnConnected?.Invoke();
            connection.OnDisconnected += () => OnDisconnected?.Invoke();
            connection.OnError += message => OnError?.Invoke(message);
            _connection = connection;
            connection.Connect(jwt);
        }

        public void Disconnect()
        {
            var connection = _connection;
            _connection = null;
            connection?.Disconnect();
        }

        public void Send(RpcRoute route, IMessage message)
        {
            var connection = _connection;
            if (connection == null || !connection.IsConnected)
            {
                throw new InvalidOperationException("Not connected");
            }
            connection.Send(route, message);
        }

        public void Send(int rpcNum, byte[] payload)
        {
            var connection = _connection;
            if (connection == null || !connection.IsConnected)
            {
                throw new InvalidOperationException("Not connected");
            }
            connection.Send(rpcNum, payload);
        }

        public void Dispose() => Disconnect();
    }
}
