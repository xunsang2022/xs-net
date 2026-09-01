using System;

namespace Game.Message.Networking
{
    /// <summary>网络连接配置（可在 Unity Inspector 中序列化）。</summary>
    [Serializable]
    public class NetworkConfig
    {
        public string Host = "127.0.0.1";
        public int TcpPort = 8082;
        public int UdpPort = 2002;
        public int KcpPort = 2003;
        public bool UseUdp = false;
        public bool UseKcp = false;
        public int ConnectTimeoutMs = 5000;
        public int WindowSize = 32;
        public int RetryIntervalMs = 200;
        public int HeartbeatIntervalMs = 3000;
        public int ReadTimeoutMs = 0; // 0 = 无读超时
    }
}
