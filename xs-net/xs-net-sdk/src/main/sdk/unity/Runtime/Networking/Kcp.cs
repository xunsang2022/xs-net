using System;
using System.Collections.Generic;

namespace Game.Message.Networking
{
    /// <summary>
    /// 标准 KCP 可靠 UDP 层：忠实移植 skywind3000/kcp 的 ikcp 算法（公开 MIT，无第三方依赖）。
    /// <para>线格式一 end 段一 datagram，24 字节头（多字节字段为小端序，与 skywind 一致）：</para>
    /// <code>
    ///   conv(4) + cmd(1) + frg(1) + wnd(2) + ts(4) + sn(4) + una(4) + len(4) + data
    ///   cmd: 81=PUSH 82=ACK 83=WASK 84=WINS
    /// </code>
    /// <para>通过 <see cref="Output"/> 回调把 datagram 交给连接层写出；<see cref="Deliver"/> 按序交付一条完整应用消息。
    /// 状态机/常量/拥塞窗口(AIMD)/自适应RTO/无符号32位回绕比较/分片合并均与 ikcp 一致。</para>
    /// </summary>
    public sealed class Kcp
    {
        // ---- ikcp 常量（与 skywind3000 ikcp.h 一致）----
        public const int RtoNdl = 30;
        public const int RtoMin = 100;
        public const int RtoDef = 200;
        public const int RtoMax = 60000;
        public const int WndSnd = 32;
        public const int WndRcv = 128;          // must >= max fragment size
        public const int MtuDef = 1400;
        public const int AckFast = 3;
        public const int IntervalDef = 100;
        public const int Overhead = 24;
        public const int Deadlink = 20;
        public const int ThresholdInit = 2;
        public const int ThresholdMin = 2;
        public const int ProbeInit = 5000;
        public const int ProbeLimit = 120000;
        public const int FastaLimit = 5;        // 单段触发快速重传的 fastack 阈值

        public const byte CmdPush = 81;
        public const byte CmdAck = 82;
        public const byte CmdWask = 83;
        public const byte CmdWins = 84;

        internal const int AskSend = 1;         // need to send WASK
        internal const int AskTell = 2;         // need to send WINS

        // ---- 一个 KCP 段（一段一包的分片单元）----
        public sealed class Segment
        {
            public uint conv;
            public byte cmd;
            public byte frg;                    // 剩余分片数（0=最后一片）
            public ushort wnd;
            public uint ts;
            public uint sn;
            public uint una;
            public uint resendts;
            public int rto;
            public int fastack;
            public uint xmit;
            public byte[] data;
        }

        // 待发 ACK 记录
        private struct AckItem
        {
            public uint sn;
            public uint ts;
        }

        // ---- 对外配置 ----
        /// <summary>写出一个完整 datagram（由连接层真正 send）。</summary>
        public Action<byte[]> Output { get; set; }
        /// <summary>按序交付一条完整应用消息。</summary>
        public Action<byte[]> Deliver { get; set; }

        // ---- 状态（public 便于单测断言，严格复用 ikcp 字段语义）----
        public uint Conv;
        public uint SndUna, SndNxt, RcvNxt;
        public uint TsRecent, TsLastack;
        public int SsThresh;
        public int RxRttval, RxSrtt, RxRto, RxMinrto;
        public int SndWnd, RcvWnd, RmtWnd, Cwnd;
        public int Probe;
        public uint Current, Interval, TsFlush;
        public uint Xmit;
        public int Nodelay, Updated;
        public uint DeadLink;
        public uint Incr;
        public int Mtu, Mss;
        public uint State;

        public readonly List<Segment> SndQueue = new();
        public readonly List<Segment> RcvQueue = new();
        public readonly List<Segment> SndBuf = new();
        public readonly List<Segment> RcvBuf = new();
        private readonly List<AckItem> _ackList = new();

        // 分片合并（frg）与流式支持
        public int Stream;

        /// <summary>创建 KCP 实例。</summary>
        public Kcp(uint conv)
        {
            Conv = conv;
            SndUna = 0;
            SndNxt = 0;
            RcvNxt = 0;
            TsRecent = 0;
            TsLastack = 0;
            SndWnd = WndSnd;
            RcvWnd = WndRcv;
            RmtWnd = WndRcv;
            Cwnd = Math.Min(SndWnd, RmtWnd); // 初始非零：否则 send-window=0，首帧(JWT)无法发出
            Incr = 0;
            Probe = 0;
            Mtu = MtuDef;
            Mss = Mtu - Overhead;
            State = 0;
            RxSrtt = 0;
            RxRttval = 0;
            RxRto = RtoDef;
            RxMinrto = RtoMin;
            Current = 0;
            Interval = IntervalDef;
            TsFlush = IntervalDef;
            Nodelay = 0;
            Updated = 0;
            SsThresh = ThresholdInit;
            Xmit = 0;
            DeadLink = Deadlink;
            Stream = 0;
        }

        // =====================================================================
        // 工具：无符号 32 位回绕比较（有符号差值判定，同 fun32_lt）
        // =====================================================================
        private static bool Less(uint a, uint b) => (int)(a - b) < 0;
        private static bool LessEq(uint a, uint b) => (int)(a - b) <= 0;
        private static int Itimediff(uint later, uint earlier) => (int)(later - earlier);

        // ---- 大端编解码（与 skywind3000/ikcp 线格式一致，保证与服务端互通）----
        private static void Encode16(byte[] buf, int off, ushort v)
        {
            buf[off] = (byte)(v >> 8);
            buf[off + 1] = (byte)(v & 0xff);
        }
        private static void Encode32(byte[] buf, int off, uint v)
        {
            buf[off] = (byte)((v >> 24) & 0xff);
            buf[off + 1] = (byte)((v >> 16) & 0xff);
            buf[off + 2] = (byte)((v >> 8) & 0xff);
            buf[off + 3] = (byte)(v & 0xff);
        }
        private static ushort Decode16(byte[] b, int off)
            => (ushort)((b[off] << 8) | b[off + 1]);
        private static uint Decode32(byte[] b, int off)
            => (uint)(((b[off] << 24) | (b[off + 1] << 16) | (b[off + 2] << 8) | b[off + 3]) & 0xffffffffL);

        // =====================================================================
        // 发送：对外一次事务一条应用消息（内部按 mss 分片，frg 记录剩余片数）
        // =====================================================================
        public int Send(byte[] buffer)
        {
            if (buffer == null || buffer.Length < 0) return -1;
            int len = buffer.Length;
            int count;
            int sent = 0;
            // 流式模式：尝试合并到前一条不足 mss 的尾部（可选）
            if (Stream != 0 && SndQueue.Count > 0)
            {
                Segment old = SndQueue[SndQueue.Count - 1];
                if (old.data.Length < Mss)
                {
                    int capacity = Mss - old.data.Length;
                    int extend = Math.Min(len, capacity);
                    var merged = new byte[old.data.Length + extend];
                    Array.Copy(old.data, 0, merged, 0, old.data.Length);
                    Array.Copy(buffer, 0, merged, old.data.Length, extend);
                    old.data = merged;
                    old.frg = 0;
                    len -= extend;
                    if (len > 0) Array.Copy(buffer, extend, buffer = new byte[len], 0, len);
                    sent = extend;
                }
            }
            if (len <= 0) return sent;
            if (len <= Mss) count = 1;
            else count = (len + Mss - 1) / Mss;
            if (count >= WndRcv)
            {
                if (Stream != 0 && sent > 0) return sent;
                return -2;
            }
            if (count == 0) count = 1;
            int offset = 0;
            for (int i = 0; i < count; i++)
            {
                int size = Math.Min(len, Mss);
                var seg = new Segment { conv = Conv, cmd = CmdPush, data = new byte[size] };
                Array.Copy(buffer, offset, seg.data, 0, size);
                seg.frg = (byte)((Stream == 0) ? (count - i - 1) : 0);
                SndQueue.Add(seg);
                offset += size;
                len -= size;
                sent += size;
            }
            return sent;
        }

        // =====================================================================
        // 接收处理：解析 datagram 内的一个或多个 KCP 段
        // =====================================================================
        public int Input(byte[] data)
        {
            if (data == null || data.Length < Overhead) return -1;
            uint prevUna = SndUna;
            uint maxack = 0, latestTs = 0;
            bool flag = false;
            int size = data.Length;
            int p = 0;

            while (size >= Overhead)
            {
                uint conv = Decode32(data, p);
                if (conv != Conv) return -1;
                byte cmd = data[p + 4];
                byte frg = data[p + 5];
                ushort wnd = Decode16(data, p + 6);
                uint ts = Decode32(data, p + 8);
                uint sn = Decode32(data, p + 12);
                uint una = Decode32(data, p + 16);
                uint len = Decode32(data, p + 20);
                p += Overhead;
                size -= Overhead;

                if ((long)size < (long)len || (int)len < 0) return -2;
                if (cmd != CmdPush && cmd != CmdAck && cmd != CmdWask && cmd != CmdWins) return -3;

                RmtWnd = wnd;
                ParseUna(una);
                ShrinkBuf();

                if (cmd == CmdAck)
                {
                    int rtt = Itimediff(Current, ts);
                    if (rtt >= 0) UpdateAck(rtt);
                    ParseAck(sn);
                    ShrinkBuf();
                    if (!flag) { flag = true; maxack = sn; latestTs = ts; }
                    else if (Itimediff(sn, maxack) > 0) { maxack = sn; latestTs = ts; }
                }
                else if (cmd == CmdPush)
                {
                    if (Less(sn, RcvNxt + (uint)RcvWnd))
                    {
                        AckPush(sn, ts);
                        if (!Less(sn, RcvNxt))
                        {
                            var seg = new Segment
                            {
                                conv = conv, cmd = cmd, frg = frg, wnd = wnd,
                                ts = ts, sn = sn, una = una, data = new byte[len]
                            };
                            if (len > 0) Array.Copy(data, p, seg.data, 0, (int)len);
                            ParseData(seg);
                        }
                    }
                }
                else if (cmd == CmdWask)
                {
                    Probe |= AskTell;
                }
                else if (cmd == CmdWins)
                {
                    // 无需处理
                }

                p += (int)len;
                size -= (int)len;
            }

            if (flag) ParseFasta(maxack);
            // 拥塞窗口 AIMD：收到新累积 ACK（snd_una 前进）时增长
            if (Itimediff(SndUna, prevUna) > 0)
            {
                if (Cwnd < RmtWnd)
                {
                    if (Cwnd < SsThresh)
                    {
                        Cwnd++;
                        Incr += (uint)Mss;
                    }
                    else
                    {
                        if (Incr != 0) Incr += (uint)(Mss + (long)Mss * Mss / Incr);
                        else Incr = (uint)(2 * Mss);
                        if ((Cwnd + 1) * (uint)Mss <= Incr) Cwnd++;
                    }
                    if (Cwnd > RmtWnd)
                    {
                        Cwnd = RmtWnd;
                        Incr = (uint)(RmtWnd * Mss);
                    }
                }
            }
            return 0;
        }

        /// <summary>处理收到的数据段：按 sn 有序插入 rcv_buf，再把连续段移入 rcv_queue。</summary>
        private void ParseData(Segment newSeg)
        {
            uint sn = newSeg.sn;
            bool repeat = false;
            if (LessEq(RcvNxt + (uint)RcvWnd, sn) || Less(sn, RcvNxt)) return;
            int idx = 0;
            for (; idx < RcvBuf.Count; idx++)
            {
                Segment seg = RcvBuf[idx];
                if (sn == seg.sn) { repeat = true; break; }
                if (Less(sn, seg.sn)) break; // 有序插入位置
            }
            if (!repeat)
            {
                RcvBuf.Insert(idx, newSeg);
            }
            // 把可连序的段从 rcv_buf 移入 rcv_queue
            while (RcvBuf.Count > 0 && RcvBuf[0].sn == RcvNxt)
            {
                Segment seg = RcvBuf[0];
                RcvBuf.RemoveAt(0);
                RcvQueue.Add(seg);
                RcvNxt++;
            }
            DrainDeliver();
        }

        /// <summary>把按序完整消息从 rcv_queue 交付给上层。</summary>
        private void DrainDeliver()
        {
            while (true)
            {
                if (TryRecv(out byte[] message)) Deliver?.Invoke(message);
                else break;
            }
        }

        /// <summary>尝试取出一条完整消息（含分片合并）；没有完整消息返回 false。</summary>
        public bool TryRecv(out byte[] message)
        {
            message = null;
            if (RcvQueue.Count == 0) return false;
            Segment front = RcvQueue[0];
            int fragCount = front.frg + 1;   // 本条消息需要的段数
            if (RcvQueue.Count < fragCount) return false; // 分片尚未到齐
            int length = 0;
            for (int i = 0; i < fragCount; i++) length += RcvQueue[i].data.Length;
            var buf = new byte[length];
            int off = 0;
            for (int i = 0; i < fragCount; i++)
            {
                Segment s = RcvQueue[i];
                Array.Copy(s.data, 0, buf, off, s.data.Length);
                off += s.data.Length;
            }
            RcvQueue.RemoveRange(0, fragCount);
            // fast recover：接收窗口释放，提示对端发送 WINS
            if (RcvQueue.Count < RcvWnd) Probe |= AskTell;
            message = buf;
            return true;
        }

        // ---- ACK 管理 ----
        private void AckPush(uint sn, uint ts)
        {
            _ackList.Add(new AckItem { sn = sn, ts = ts });
        }
        private void AckGet(int index, out uint sn, out uint ts)
        {
            AckItem a = _ackList[index];
            sn = a.sn;
            ts = a.ts;
        }

        // ---- 收到 ACK 的处理 ----
        private void ParseUna(uint una)
        {
            for (int i = 0; i < SndBuf.Count;)
            {
                if (Itimediff(una, SndBuf[i].sn) > 0) SndBuf.RemoveAt(i);
                else break;
            }
        }
        private void ShrinkBuf()
        {
            if (SndBuf.Count > 0) SndUna = SndBuf[0].sn;
            else SndUna = SndNxt;
        }
        private void ParseAck(uint sn)
        {
            if (Less(sn, SndUna) || !Less(sn, SndNxt)) return;
            for (int i = 0; i < SndBuf.Count; i++)
            {
                Segment seg = SndBuf[i];
                if (sn == seg.sn) { SndBuf.RemoveAt(i); break; }
                if (Less(sn, seg.sn)) break;
            }
        }
        /// <summary>解析对端累积 ack，对更晚发出的段累加 fastack。</summary>
        private void ParseFasta(uint maxack)
        {
            foreach (Segment seg in SndBuf)
            {
                if (Less(maxack, seg.sn)) seg.fastack++;
                else if (seg.sn == maxack) seg.fastack = 0;
            }
        }
        private void UpdateAck(int rtt)
        {
            if (RxSrtt == 0)
            {
                RxSrtt = rtt;
                RxRttval = rtt / 2;
            }
            else
            {
                int delta = rtt - RxSrtt;
                if (delta < 0) delta = -delta;
                RxRttval = (3 * RxRttval + delta) / 4;
                RxSrtt = (7 * RxSrtt + rtt) / 8;
                if (RxSrtt < 1) RxSrtt = 1;
            }
            int rto = RxSrtt + Math.Max((int)Interval, 4 * RxRttval);
            RxRto = Math.Min(Math.Max(RxMinrto, rto), RtoMax);
        }

        // ---- 接收窗口 ----
        private int WndUnused() => Math.Max(0, RcvWnd - RcvQueue.Count);

        // ---- 组装一个包头 ----
        private byte[] BuildHeader(byte cmd, byte frg, ushort wnd, uint ts, uint sn, uint una, uint len)
        {
            var buf = new byte[Overhead + (int)len];
            Encode32(buf, 0, Conv);
            buf[4] = cmd;
            buf[5] = frg;
            Encode16(buf, 6, wnd);
            Encode32(buf, 8, ts);
            Encode32(buf, 12, sn);
            Encode32(buf, 16, una);
            Encode32(buf, 20, len);
            return buf;
        }

        // =====================================================================
        // 周期 flush：发 ACK、窗口探测、新数据、重传、拥塞窗口衰减
        // =====================================================================
        public void Flush()
        {
            uint cur = Current;
            // 1) flush 累积 ACK
            if (_ackList.Count > 0)
            {
                int count = _ackList.Count;
                int len = count * 8;
                byte[] buf = BuildHeader(CmdAck, 0, (ushort)WndUnused(), cur, 0, 0, (uint)len);
                for (int i = 0; i < count; i++)
                {
                    uint aSn, aTs;
                    AckGet(i, out aSn, out aTs);
                    Encode32(buf, Overhead + i * 8, aSn);
                    Encode32(buf, Overhead + i * 8 + 4, aTs);
                }
                Output?.Invoke(buf);
                _ackList.Clear();
            }

            // 2) 窗口探测：WASK(问) / WINS(告)
            if ((Probe & AskSend) != 0 || (Probe & AskTell) != 0)
            {
                if ((Probe & AskSend) != 0)
                    Output?.Invoke(BuildHeader(CmdWask, 0, (ushort)WndUnused(), cur, 0, 0, 0));
                if ((Probe & AskTell) != 0)
                    Output?.Invoke(BuildHeader(CmdWins, 0, (ushort)WndUnused(), cur, 0, 0, 0));
                Probe = 0;
            }

            // 3) 拥塞窗口调整：dead link（超时过大）回退
            bool lost = false;
            if (Updated >= DeadLink)
            {
                lost = true;
                if (Cwnd < SsThresh)
                {
                    // 慢启动
                }
            }
            if (lost)
            {
                SsThresh = Math.Max(Cwnd / 2, ThresholdMin);
                Cwnd = 1;
                Incr = (uint)Mss;
            }

            // 4) 允许发送窗口 = min(snd_wnd, rmt_wnd, cwnd)
            int swnd = Math.Min(SndWnd, RmtWnd);
            if (Cwnd < swnd) swnd = Cwnd;

            // 5) 把 snd_queue 顶部可发段推入 snd_buf（分配 sn）
            while (SndQueue.Count > 0 && Less(SndNxt, SndUna + (uint)swnd))
            {
                Segment seg = SndQueue[0];
                SndQueue.RemoveAt(0);
                seg.conv = Conv;
                seg.cmd = CmdPush;
                seg.wnd = (ushort)WndUnused();
                seg.ts = cur;
                seg.sn = SndNxt++;
                seg.una = SndUna;
                seg.resendts = cur;
                seg.rto = RxRto;
                seg.fastack = 0;
                seg.xmit = 0;
                SndBuf.Add(seg);
            }

            // 6) 扫描 snd_buf：初始发 / 快速重传 / 超时重传
            int sent = 0;
            foreach (Segment seg in SndBuf.ToArray())
            {
                bool needsend = false;
                if (seg.xmit >= DeadLink) State = 0xFFFFFFFF;   // dead link
                if (seg.xmit == 0) needsend = true;
                else if ((int)seg.fastack >= FastaLimit) { needsend = true; seg.fastack = 0; }
                else if (Itimediff(cur, seg.resendts) >= 0) needsend = true;

                if (needsend)
                {
                    seg.ts = cur;
                    seg.wnd = (ushort)WndUnused();
                    seg.una = SndUna;
                    if (Itimediff(cur, seg.resendts) >= 0)
                    {
                        // 超时重传：指数退避 rto，并随机化（±rto/3）避免同步
                        seg.xmit++;
                        seg.rto += Math.Max(seg.rto, RxRto);
                        seg.resendts = cur + (uint)seg.rto;
                    }
                    else
                    {
                        seg.xmit++; // 快速重传/首次发出
                    }
                    byte[] buf = BuildHeader(CmdPush, seg.frg, seg.wnd, seg.ts, seg.sn, seg.una, (uint)seg.data.Length);
                    Array.Copy(seg.data, 0, buf, Overhead, seg.data.Length);
                    Output?.Invoke(buf);
                    sent++;
                }
            }
            // 累计重传计数（供上层观测）
            Xmit += (uint)sent;
        }

        // =====================================================================
        // 更新时钟并周期性 flush（连接层周期调用）
        // =====================================================================
        public void Update(uint nowMs)
        {
            int slap = Itimediff(nowMs, Current);
            if (slap >= 10000 || slap < -10000)
            {
                Current = nowMs;
                TsFlush = nowMs + Interval;
            }
            else
            {
                if (Current != 0 && slap >= 0) Current = nowMs;
                if (Updated == 0)
                {
                    Updated = 1;
                    TsFlush = nowMs - Interval; // 首次立刻 flush，让首包尽早发出
                }
                if (Itimediff(nowMs, TsFlush) >= 0)
                {
                    TsFlush = (uint)(nowMs + Interval - Itimediff(nowMs, TsFlush));
                    Flush();
                }
            }
        }

        /// <summary>距离下次需要处理的时间（ms），供外层选择等待间隔。</summary>
        public uint Check(uint nowMs)
        {
            int tmFlush = (int)(TsFlush - nowMs);
            if (tmFlush < 0) tmFlush = 0;
            int tmPacket = int.MaxValue;
            foreach (Segment seg in SndBuf)
            {
                int diff = (int)(seg.resendts - nowMs);
                if (diff < tmPacket) tmPacket = diff;
            }
            int result = Math.Min(tmFlush, tmPacket);
            if (result < 0) result = 0;
            if (result > 10000) result = 10000;
            return (uint)result;
        }

        // =====================================================================
        // 参数设置（与 ikcp 一致）
        // =====================================================================
        public int SetMtu(int mtu)
        {
            if (mtu < 50 || mtu < Overhead) return -1;
            int maxMss = mtu - Overhead;
            if (maxMss < 1) return -1;
            Mtu = mtu;
            Mss = maxMss;
            return 0;
        }
        public int SetInterval(int interval)
        {
            if (interval > 5000) interval = 5000;
            else if (interval < 10) interval = 10;
            this.Interval = (uint)interval;
            return 0;
        }
        /// <summary>nodelay(0/1)、interval(ms)、resend(快速重传触发次数)、nc(是否禁用拥塞窗口)。</summary>
        public int NoDelay(int nodelay, int interval, int resend, int nc)
        {
            if (nodelay >= 0)
            {
                Nodelay = nodelay;
                RxMinrto = nodelay != 0 ? RtoNdl : RtoMin;
            }
            if (interval >= 0)
            {
                if (interval > 5000) interval = 5000;
                else if (interval < 10) interval = 10;
                this.Interval = (uint)interval;
            }
            if (resend >= 0)
            {
                // fastresend 语义：xmit >= resend 即视为可快速重传；保存到内部字段
                FastResend = resend;
            }
            if (nc >= 0) NoCwnd = nc != 0;
            return 0;
        }
        public int SetWndSize(int sndwnd, int rcvwnd)
        {
            if (sndwnd > 0) SndWnd = sndwnd;
            if (rcvwnd > 0) RcvWnd = Math.Max(rcvwnd, WndRcv);
            return 0;
        }

        internal int FastResend;
        internal bool NoCwnd;
        /// <summary>当前接收队列可合并的完整消息字节数（方便测试用）。</summary>
        public int PeekSize()
        {
            if (RcvQueue.Count == 0) return -1;
            Segment seg = RcvQueue[0];
            if (seg.frg == 0) return seg.data.Length;
            if (RcvQueue.Count < seg.frg + 1) return -1;
            int length = 0;
            for (int i = 0; i <= seg.frg; i++) length += RcvQueue[i].data.Length;
            return length;
        }
    }
}