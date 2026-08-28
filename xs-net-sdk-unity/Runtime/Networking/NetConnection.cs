using System;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>
    /// 连接抽象基类：统一 TCP/UDP 的连连接、断开、发送与事件。
    /// 子类实现具体传输（<see cref="TcpConnection"/> / <see cref="UdpConnection"/>）。
    /// </summary>
    public abstract class NetConnection : IDisposable
    {
        public event Action OnConnected;
        public event Action OnDisconnected;
        public event Action<string> OnError;

        protected readonly NetworkConfig Config;
        protected readonly MessageDispatcher Dispatcher;

        protected NetConnection(NetworkConfig config, MessageDispatcher dispatcher)
        {
            Config = config;
            Dispatcher = dispatcher;
        }

        public abstract void Connect(string jwt);
        public abstract void Disconnect();
        public abstract bool IsConnected { get; }

        public abstract void Send(RpcRoute route, IMessage message);
        public abstract void Send(int rpcNum, byte[] payload);

        protected void RaiseConnected() => OnConnected?.Invoke();
        protected void RaiseDisconnected() => OnDisconnected?.Invoke();
        protected void RaiseError(string message) => OnError?.Invoke(message);

        public virtual void Dispose() => Disconnect();
    }
}
