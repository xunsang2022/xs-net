using System;
using System.Collections.Generic;
using System.Reflection;
using Google.Protobuf;

namespace Game.Message.Networking
{
    /// <summary>按 <see cref="RpcRoute"/> 分发收到的服务端消息到已注册处理器。</summary>
    public sealed class MessageDispatcher
    {
        private readonly Dictionary<RpcRoute, Action<byte[]>> _handlers = new();

        /// <summary>注册原始 payload 处理器。</summary>
        public void Register(RpcRoute route, Action<byte[]> handler)
        {
            _handlers[route] = handler;
        }

        /// <summary>注册强类型处理器，内部用生成消息类型的 <c>Parser</c> 反序列化。</summary>
        public void Register<T>(RpcRoute route, Action<T> handler) where T : IMessage<T>, new()
        {
            var parser = (MessageParser<T>)typeof(T).GetProperty("Parser", BindingFlags.Public | BindingFlags.Static)
                .GetValue(null);
            Register(route, payload => handler(parser.ParseFrom(payload)));
        }

        public void Unregister(RpcRoute route)
        {
            _handlers.Remove(route);
        }

        public void Clear()
        {
            _handlers.Clear();
        }

        public void Dispatch(RpcRoute route, byte[] payload)
        {
            if (_handlers.TryGetValue(route, out var handler))
            {
                handler(payload);
            }
        }
    }
}
