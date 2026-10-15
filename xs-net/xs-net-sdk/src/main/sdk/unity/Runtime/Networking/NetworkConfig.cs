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
        public int RetryIntervalMs = 50;
        // FEC 每组数据/冗余分片数，FecK=0 表示关闭
        public int FecK = 0;
        public int FecM = 1;
        public int HeartbeatIntervalMs = 3000;
        public int ReadTimeoutMs = 0; // 0 = 无读超时
        public bool AutoReconnect = true;
        public int ReconnectDelayMs = 500;
        public int MaxReconnectAttempts = 10;
        public uint ConnectionRandom = 0;
    }
}
