using System;
using System.Threading;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>
    /// 网络客户端门面：按 <see cref="NetworkConfig.UseKcp"/> / <see cref="NetworkConfig.UseUdp"/> 选择 KCP、UDP 或 TCP 连接，
    /// 封装连接、JWT 认证、消息发送与上层事件。使用 <see cref="Dispatcher"/> 注册消息处理器。
    /// </summary>
    public sealed class GameNetworkClient : IDisposable
    {
        private readonly NetworkConfig _config;
        private NetConnection _connection;
        private string _jwt;
        private Timer _reconnectTimer;
        private int _reconnectAttempts;
        private bool _manualDisconnect = true;

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
            _jwt = jwt ?? string.Empty;
            if (_config.ConnectionRandom == 0)
            {
                _config.ConnectionRandom = (uint)Random.Shared.NextInt64(1, uint.MaxValue);
            }
            _manualDisconnect = false;
            _reconnectAttempts = 0;
            ConnectInternal();
        }

        private void ConnectInternal()
        {
            NetConnection connection = _config.UseKcp
                ? new KcpConnection(_config, Dispatcher)
                : _config.UseUdp
                    ? new UdpConnection(_config, Dispatcher)
                    : new TcpConnection(_config, Dispatcher);
            connection.OnConnected += () =>
            {
                _reconnectAttempts = 0;
                OnConnected?.Invoke();
            };
            connection.OnDisconnected += () =>
            {
                OnDisconnected?.Invoke();
                ScheduleReconnect();
            };
            connection.OnError += message => OnError?.Invoke(message);
            _connection = connection;
            try
            {
                connection.Connect(_jwt);
            }
            catch (Exception exception)
            {
                OnError?.Invoke(exception.Message);
                ScheduleReconnect();
            }
        }

        public void Disconnect()
        {
            _manualDisconnect = true;
            _reconnectTimer?.Dispose();
            _reconnectTimer = null;
            var connection = _connection;
            _connection = null;
            connection?.Disconnect();
        }

        private void ScheduleReconnect()
        {
            if (_manualDisconnect || !_config.AutoReconnect || string.IsNullOrEmpty(_jwt)
                || _reconnectAttempts >= Math.Max(0, _config.MaxReconnectAttempts)
                || _reconnectTimer != null)
            {
                return;
            }
            _reconnectAttempts++;
            _reconnectTimer = new Timer(_ =>
            {
                _reconnectTimer?.Dispose();
                _reconnectTimer = null;
                if (!_manualDisconnect) ConnectInternal();
            }, null, Math.Max(0, _config.ReconnectDelayMs), Timeout.Infinite);
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
