package com.game.gateway.kcp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 自研标准 KCP 可靠 UDP 协议核心类（忠实移植 skywind3000/kcp 的 ikcp 算法，MIT 协议，无第三方依赖）。
 *
 * <p><b>标准线格式（一包一段，大端序，overhead = {@value #IKCP_OVERHEAD} 字节）：</b>
 * conv(4) + cmd(1) + frg(1) + wnd(2) + ts(4) + sn(4) + una(4) + len(4) + [data]。</p>
 *
 * <p>cmd 取值：{@link #IKCP_CMD_PUSH}=81(PUSH)、{@link #IKCP_CMD_ACK}=82(ACK)、
 * {@link #IKCP_CMD_WASK}=83(WASK)、{@link #IKCP_CMD_WINS}=84(WINS)。</p>
 *
 * <p>算法要点：自适应 RTO（RFC6298 式 srtt/rttval/rto 滑动 EWM）、拥塞窗口 AIMD
 * （cwnd / ssthresh / incr，慢启动→拥塞避免，丢包快速重传时 ssthresh 减半）、
 * 快速 ACK 计数器（fastack++ ≥ fastresend 阈值触发快速重传，默认 2）、死链阈值
 * {@value #IKCP_DEADLINK}、无符号 32 位序号回绕比较（{@link #itimediff 距离差}）、
 * 四条缓冲队列（snd_queue / rcv_queue / snd_buf / rcv_buf）、基于 MTU 的 mss 分片与重组。</p>
 */
public final class Kcp {

    /* ---------- 常量（与 ikcp.c 严格一致） ---------- */
    static final int IKCP_RTO_NDL = 30;
    static final int IKCP_RTO_MIN = 100;
    static final int IKCP_RTO_DEF = 200;
    static final int IKCP_RTO_MAX = 60000;
    static final int IKCP_CMD_PUSH = 81;
    static final int IKCP_CMD_ACK = 82;
    static final int IKCP_CMD_WASK = 83;
    static final int IKCP_CMD_WINS = 84;
    static final int IKCP_ASK_SEND = 1;
    static final int IKCP_ASK_TELL = 2;
    static final int IKCP_OVERHEAD = 24;
    static final int IKCP_DEADLINK = 20;
    static final int IKCP_THRESH_INIT = 2;
    static final int IKCP_THRESH_MIN = 2;
    static final int IKCP_PROBE_INIT = 7000;
    static final int IKCP_PROBE_LIMIT = 120000;
    static final int OVERHEAD = IKCP_OVERHEAD;

    /** KCP 数据段（等价 ikcp 的 IQSEGMENT）。32 位序号/时间戳用 int 存储以自然回绕，比较走 {@link #itimediff}。 */
    private static final class Segment {
        int conv;
        int cmd;
        int frg;
        int wnd;
        int ts;
        int sn;
        int una;
        int len;
        int resendts;
        int rto;
        int fastack;
        int xmit;
        byte[] data;
    }

    /* ---------- 状态 ---------- */
    private int conv;
    private int mtu;
    private int mss;
    private int state;

    private int sndUna;
    private int sndNxt;
    private int rcvNxt;

    /* 拥塞控制（AIMD） */
    private int ssthresh = IKCP_THRESH_INIT;
    private int cwnd;
    private int incr;
    private int nocwnd;

    /* 自适应 RTO */
    private int rxRttval;
    private int rxSrtt;
    private int rxRto = IKCP_RTO_DEF;
    private int rxMinrto = IKCP_RTO_MIN;

    private int sndWnd = 32;
    private int rcvWnd = 32;
    private int rmtWnd = 32;
    private int probe;
    private int interval = 100;
    private long current;
    private long tsFlush;
    private boolean updated;
    private long tsProbe;
    private int probeWait;
    private int fastresend;   // >0 时为快速重传阈值

    /* ---------- 四条双向队列（与 ikcp 内部缓冲一致） ----------
     * 非线程安全：本类所有字段（含下列队列）在“仅由持有它的 KcpSession 监视器串行访问”时才安全。
     * 该监视器 = KcpSession 实例：KcpSession.sendBytes/write（下行 ChannelActor 线程）已用 synchronized 方法，
     * 与 KcpAuthHandler 的 synchronized (session) 块（业务线程池 input、后台定时 update）共用同一监视器，
     * 因而这些集合保持 ArrayList/ArrayDeque 即可，无需并发容器（并发容器会破坏这里的迭代/有序删除语义）。 */
    private final ArrayDeque<Segment> sndQueue = new ArrayDeque<>();
    private final ArrayDeque<Segment> rcvQueue = new ArrayDeque<>();
    private final List<Segment> sndBuf = new ArrayList<>();
    private final List<Segment> rcvBuf = new ArrayList<>();
    /** 回应当前待发送的 ACK 记录：相邻两项 (sn, ts) 交替存放。 */
    private final ArrayList<Integer> acklist = new ArrayList<>();

    private BiConsumer<byte[], Integer> output;

    public Kcp() {
        this(1400);
    }

    public Kcp(int mtu) {
        this(0, mtu);
    }

    public Kcp(int conv, int mtu) {
        this.conv = conv;
        this.mtu = Math.max(mtu, IKCP_OVERHEAD + 26);
        this.mss = this.mtu - IKCP_OVERHEAD;
    }

    /** 设置输出回调：flush 时每个待发段调用一次，参数为 (序列化后的整包字节, 有效长度)。 */
    public void setOutput(BiConsumer<byte[], Integer> output) {
        this.output = output;
    }

    /* ==================== 对外收发 API ==================== */

    /** 发送一条完整消息。超过单段 mss 自动按 mss 分片（frg 递减，最后一片 frg=0）。入队 snd_queue 待 flush。 */
    public void send(byte[] buffer, int offset, int len) {
        if (buffer == null || len < 0 || offset < 0) {
            throw new IllegalArgumentException("invalid send args");
        }
        int count = len <= 0 ? 1 : (len + mss - 1) / mss;
        int src = offset;
        int remaining = len;
        for (int i = 0; i < count; i++) {
            int size = Math.min(remaining, mss);
            if (size < 0) {
                size = 0;
            }
            Segment seg = newSegment(size);
            seg.cmd = IKCP_CMD_PUSH;
            seg.frg = count - i - 1;
            if (size > 0) {
                System.arraycopy(buffer, src, seg.data, 0, size);
            }
            src += size;
            remaining -= size;
            sndQueue.addLast(seg);
        }
    }

    /** 便捷重载：发送整段字节（空数组表示零长度消息，如 auth-ok）。 */
    public void send(byte[] buffer) {
        if (buffer == null) {
            send(new byte[0], 0, 0);
            return;
        }
        send(buffer, 0, buffer.length);
    }

    /** 计算可交付消息大小：遍历 rcv_queue 累加，直到某段 frg==0（末片）。缺片或队列为空返回 -1。 */
    public int peekSize() {
        int length = 0;
        for (Segment seg : rcvQueue) {
            length += seg.len;
            if (seg.frg == 0) {
                return length;
            }
        }
        return -1;
    }

    /** 取出一条可交付消息；无完整消息返回 null。多片按队列顺序合并为单个字节数组。 */
    public byte[] recv() {
        int peek = peekSize();
        if (peek < 0) {
            return null;
        }
        byte[] out = new byte[peek];
        int offset = 0;
        while (!rcvQueue.isEmpty()) {
            Segment seg = rcvQueue.removeFirst();
            if (seg.len > 0) {
                System.arraycopy(seg.data, 0, out, offset, seg.len);
            }
            offset += seg.len;
            if (seg.frg == 0) {
                break;
            }
        }
        return out;
    }

    /** 读入对端一段 UDP 载荷（可含多条 KCP 帧），逐帧解析并按 cmd 分发。 */
    public void input(byte[] buffer) {
        if (buffer == null || buffer.length < IKCP_OVERHEAD) {
            throw new IllegalArgumentException("empty input");
        }
        input(buffer, 0, buffer.length);
    }

    /* -------------------- 帧解析（input 子逻辑） -------------------- */

    private void input(byte[] buf, int off, int len) {
        int cursor = off;
        int end = off + len;
        while (cursor + IKCP_OVERHEAD <= end) {
            int cmd = buf[cursor + 4] & 0xFF;
            int frg = buf[cursor + 5] & 0xFF;
            int wnd = ((buf[cursor + 6] & 0xFF) << 8) | (buf[cursor + 7] & 0xFF);
            int ts = readInt(buf, cursor + 8);
            int sn = readInt(buf, cursor + 12);
            int una = readInt(buf, cursor + 16);
            int l = readInt(buf, cursor + 20);
            cursor += IKCP_OVERHEAD;
            if (l < 0 || cursor + l > end || (cmd != IKCP_CMD_PUSH && cmd != IKCP_CMD_ACK
                    && cmd != IKCP_CMD_WASK && cmd != IKCP_CMD_WINS)) {
                return;
            }
            switch (cmd) {
                case IKCP_CMD_ACK:
                    parseAck(sn);
                    parseUna(una);
                    probeWait = 0;
                    break;
                case IKCP_CMD_PUSH:
                    parseUna(una);
                    parseFastack(sn);
                    // 与 ikcp 一致：仅当序号 >= rcv_nxt 才入队，避免已交付段（重传）滞留 rcvBuf 头部阻塞后续按序前移
                    if (itimediff(sn, rcvNxt) >= 0) {
                        rcvBufInsert(sn, frg, ts, buf, cursor, l);
                    }
                    acklist.add(sn);
                    acklist.add(ts);
                    break;
                case IKCP_CMD_WASK:
                    probe |= IKCP_ASK_TELL;
                    break;
                case IKCP_CMD_WINS:
                    rmtWnd = wnd;
                    break;
                default:
                    break;
            }
            cursor += l;
        }
    }

    /** 累积确认指定的精确 sn 段（ACK），并做 RFC6298 自适应 RTO 采样。 */
    private void parseAck(int sn) {
        if (itimediff(sn, sndUna) < 0 || itimediff(sn, sndNxt) >= 0) {
            return;
        }
        for (Iterator<Segment> it = sndBuf.iterator(); it.hasNext(); ) {
            Segment seg = it.next();
            if (itimediff(sn, seg.sn) < 0) {
                break;
            }
            if (itimediff(sn, seg.sn) == 0) {
                int rtt = seg.xmit > 0 ? itimediff(currentMs(), seg.ts) : -1;
                if (rtt >= 0) {
                    updateRto(rtt);
                }
                // AIMD：收到有效 ACK 后增长 cwnd/incr
                onCwndAck();
                it.remove();
                refreshSndUna();
                break;
            }
        }
        probeWait = 0;
    }

    /** 对端累积 una：移除 sndBuf 中 sn<una 的段。 */
    private void parseUna(int una) {
        boolean removed = false;
        while (!sndBuf.isEmpty() && itimediff(una, sndBuf.get(0).sn) > 0) {
            sndBuf.remove(0);
            removed = true;
        }
        if (removed) {
            refreshSndUna();
        }
    }

    /** 快速 ACK 计数：对 sndBuf 中 sn < ackSn 的段 fastack++。 */
    private void parseFastack(int ackSn) {
        for (Segment seg : sndBuf) {
            if (itimediff(ackSn, seg.sn) > 0) {
                seg.fastack++;
            } else {
                break;
            }
        }
    }

    /** 插入 rcv_buf（去重 + 有序），并把连续到达段前移到 rcv_queue。 */
    private void rcvBufInsert(int sn, int frg, int ts, byte[] src, int dataOff, int len) {
        // 窗口外（超窗）丢弃
        if (itimediff(sn, rcvNxt + rcvWnd) >= 0) {
            return;
        }
        int idx = 0;
        for (; idx < rcvBuf.size(); idx++) {
            int c = itimediff(sn, rcvBuf.get(idx).sn);
            if (c < 0) {
                break;
            }
            if (c == 0) {
                return; // 重复段
            }
        }
        Segment seg = newSegment(len);
        seg.cmd = IKCP_CMD_PUSH;
        seg.frg = frg;
        seg.ts = ts;
        seg.sn = sn;
        if (len > 0) {
            System.arraycopy(src, dataOff, seg.data, 0, len);
        }
        rcvBuf.add(idx, seg);

        // 连续段（sn == rcv_nxt）前移
        while (!rcvBuf.isEmpty() && itimediff(rcvBuf.get(0).sn, rcvNxt) == 0) {
            rcvQueue.addLast(rcvBuf.remove(0));
            rcvNxt++;
        }
    }

    /* -------------------- AIMD 拥塞控制 -------------------- */

    /** 收到一个有效 ACK 后按 AIMD 增长 cwnd（incr 累计字节，cwnd = incr/mss）。 */
    private void onCwndAck() {
        if (nocwnd != 0) {
            return;
        }
        if (cwnd == 0) {
            incr = mss;
            cwnd = Math.max(1, incr / mss);
        } else if (cwnd < ssthresh) {
            // 慢启动：每 RTT 翻倍
            incr += mss;
            if (incr < 0) {
                incr = Integer.MAX_VALUE / 2;
            }
            cwnd = Math.max(cwnd + 1, incr / mss);
        } else {
            // 拥塞避免：每 cwnd 个 ACK 增加 1 段
            long add = (long) mss * mss / Math.max(cwnd, 1);
            incr = (int) Math.min((long) incr + add, Integer.MAX_VALUE / 2);
            int next = Math.max(1, incr / mss);
            if (next > cwnd) {
                cwnd = next;
            }
        }
    }

    /** 丢包时收缩拥塞窗口：ssthresh = cwnd/2，cwnd 回落。 */
    private void onCwndLoss() {
        if (nocwnd != 0) {
            return;
        }
        ssthresh = Math.max(cwnd / 2, IKCP_THRESH_MIN);
        cwnd = Math.max(1, cwnd / 2);
        incr = cwnd * mss;
    }

    /** 当前发送窗口可用于发送的段数（受本地 snd_wnd、远端窗口与拥塞窗口共同约束）。 */
    private int sendLimit() {
        int limit = Math.min(sndWnd, Math.max(0, rmtWnd));
        if (nocwnd == 0 && cwnd > 0) {
            limit = Math.min(limit, Math.max(1, cwnd));
        }
        return limit;
    }

    /* -------------------- update / flush -------------------- */

    /** 驱动协议演进：按 interval 节拍 flush，tick 为系统毫秒（由外部定时器调用）。 */
    public void update(long nowMs) {
        current = nowMs;
        if (!updated) {
            updated = true;
            tsFlush = nowMs;
        }
        int slap = itimediff((int) nowMs, (int) tsFlush);
        if (slap > 10000 || slap < -10000) {
            tsFlush = nowMs;
            slap = 0;
        }
        if (slap >= 0) {
            tsFlush += interval;
            if (itimediff((int) nowMs, (int) tsFlush) >= 0) {
                tsFlush = nowMs + interval;
            }
            flush((int) nowMs);
        }
        // 窗口探测调度（ASK_SEND 退避）
        if (probeWait != 0) {
            if (itimediff((int) nowMs, (int) tsProbe) >= 0) {
                probe |= IKCP_ASK_SEND;
                if (probeWait < IKCP_PROBE_LIMIT) {
                    probeWait *= 2;
                    if (probeWait > IKCP_PROBE_LIMIT) {
                        probeWait = IKCP_PROBE_LIMIT;
                    }
                }
                tsProbe = nowMs + probeWait;
            }
        }
    }

    /** 立即发送一次当前缓冲（通常由 update 驱动，也可在 S2C 下行后显式调用强制刷出）。 */
    public void flush(int currentMs) {
        current = currentMs;
        if (!updated) {
            updated = true;
            tsFlush = currentMs;
        }
        if (output == null) {
            return;
        }

        // 1) 回应窗口询问 ASK_TELL → 发 WINS
        if ((probe & IKCP_ASK_TELL) != 0) {
            sendFrame(IKCP_CMD_WINS, 0, wndUnused(), 0, 0, rcvNxt, null, 0);
            probe &= ~IKCP_ASK_TELL;
        }

        // 2) ACK 段：回执先前收到的 PUSH
        for (int i = 0; i + 1 < acklist.size(); i += 2) {
            int ackSn = acklist.get(i);
            int ackTs = acklist.get(i + 1);
            sendFrame(IKCP_CMD_ACK, 0, wndUnused(), ackSn, ackTs, rcvNxt, null, 0);
        }
        acklist.clear();

        // 3) 重传扫描：超时 或 fastack≥fastresend 快速重传；死链检测
        int fastThreshold = fastresend > 0 ? fastresend : 2;
        for (Segment seg : sndBuf) {
            boolean needResend = itimediff(currentMs, seg.resendts) >= 0 || seg.fastack >= fastThreshold;
            if (needResend) {
                if (seg.xmit >= IKCP_DEADLINK) {
                    state = -1;
                    return;
                }
                seg.xmit++;
                seg.resendts = currentMs + seg.rto;
                seg.fastack = 0;
                seg.ts = currentMs;
                onCwndLoss();
                sendFrame(IKCP_CMD_PUSH, seg.frg, wndUnused(), seg.sn, seg.ts, rcvNxt, seg.data, seg.len);
            }
        }

        // 4) 新数据发送（受窗口与拥塞窗口约束）
        int canSend = Math.min(sendLimit(), Math.max(0, rmtWnd)) - itimediff(sndNxt, sndUna);
        while (canSend > 0) {
            if (sndQueue.isEmpty()) {
                break;
            }
            Segment seg = sndQueue.removeFirst();
            seg.conv = conv;
            seg.cmd = IKCP_CMD_PUSH;
            seg.ts = currentMs;
            seg.sn = sndNxt++;
            seg.una = rcvNxt;
            seg.wnd = wndUnused();
            seg.resendts = currentMs + rxRto;
            seg.rto = rxRto;
            seg.fastack = 0;
            seg.xmit = 1;
            sndBuf.add(seg);
            refreshSndUna();
            sendFrame(IKCP_CMD_PUSH, seg.frg, seg.wnd, seg.sn, seg.ts, seg.una, seg.data, seg.len);
            canSend--;
        }
    }

    /* -------------------- 工具（数据处理） -------------------- */

    /** 序列化一个标准 KCP 帧并通过 output 回调发出。 */
    private void sendFrame(int cmd, int frg, int wnd, int sn, int ts, int una, byte[] data, int len) {
        int total = IKCP_OVERHEAD + len;
        byte[] out = new byte[total];
        writeInt(out, 0, conv);
        out[4] = (byte) cmd;
        out[5] = (byte) (frg & 0xFF);
        out[6] = (byte) (wnd >>> 8);
        out[7] = (byte) wnd;
        writeInt(out, 8, ts);
        writeInt(out, 12, sn);
        writeInt(out, 16, una);
        writeInt(out, 20, len);
        if (data != null && len > 0) {
            System.arraycopy(data, 0, out, IKCP_OVERHEAD, len);
        }
        if (output != null) {
            output.accept(out, total);
        }
    }

    private void refreshSndUna() {
        sndUna = sndBuf.isEmpty() ? sndNxt : sndBuf.get(0).sn;
    }

    private int currentMs() {
        return (int) curstamp();
    }

    private long curstamp() {
        return current != 0 ? current : System.currentTimeMillis();
    }

    private void updateRto(int rtt) {
        int delta;
        if (rxSrtt == 0) {
            rxSrtt = rtt;
            rxRttval = rtt / 2;
        } else {
            delta = rtt - rxSrtt;
            if (delta < 0) {
                delta = -delta;
            }
            rxRttval = (3 * rxRttval + delta) / 4;
            rxSrtt = (7 * rxSrtt + rtt) / 8;
            if (rxSrtt < 1) {
                rxSrtt = 1;
            }
        }
        int rto = rxSrtt + Math.max(1, rxRttval * 4);
        if (rto < 0) {
            rto = 0;
        }
        rxRto = ibound(rxMinrto, rto, IKCP_RTO_MAX);
    }

    private int wndUnused() {
        return Math.max(rcvWnd - (rcvQueue.size() + rcvBuf.size()), 0);
    }

    /* -------------------- 配置 -------------------- */

    /** 与 ikcp_nodelay 一致：(nodelay, interval, fastresend, nocwnd)，各参数 <0 表示不修改。 */
    public void setNodelay(int nodelay, int interval, int fastresend, int nocwnd) {
        if (nodelay >= 0) {
            rxMinrto = nodelay != 0 ? IKCP_RTO_NDL : IKCP_RTO_MIN;
        }
        if (interval >= 0) {
            this.interval = ibound(10, interval, 5000);
        }
        if (fastresend >= 0) {
            this.fastresend = fastresend;
        }
        if (nocwnd >= 0) {
            this.nocwnd = nocwnd;
        }
    }

    /** 设置协议节拍间隔(ms)。 */
    public void setInterval(int interval) {
        this.interval = ibound(10, interval, 5000);
    }

    /** 本端 KCP 会话号。 */
    public int conv() {
        return conv;
    }

    /** 读取一段 UDP 载荷中首个 KCP 帧的会话号；调用方需保证长度不小于 {@link #OVERHEAD}（会话号可为负，不能以符号判非法）。 */
    public static int peekConv(byte[] datagram) {
        return readInt(datagram, 0);
    }

    /** 是否存在需要定时驱动的状态（待发数据、未确认在途、待回 ACK、窗口探测）。供后台扫描跳过空闲会话。 */
    public boolean hasPendingWork() {
        return !sndQueue.isEmpty() || !sndBuf.isEmpty() || !acklist.isEmpty() || probe != 0;
    }

    /** 设置收发窗口大小。 */
    public void setWndsize(int sndwnd, int rcvwnd) {
        if (sndwnd > 0) {
            sndWnd = sndwnd;
        }
        if (rcvwnd > 0) {
            rcvWnd = rcvwnd;
        }
    }

    /* -------------------- 工具 -------------------- */

    /** 无符号 32 位回绕距离差（等价 ikcp 的 _itimediff，(later-earlier) mod 2^32 按有符号解释）。 */
    private static int itimediff(int later, int earlier) {
        return later - earlier;
    }

    private static int ibound(int lower, int middle, int upper) {
        return Math.min(upper, Math.max(lower, middle));
    }

    private static Segment newSegment(int len) {
        Segment seg = new Segment();
        seg.len = len;
        seg.data = new byte[Math.max(len, 0)];
        seg.frg = 0;
        seg.wnd = 0;
        seg.ts = 0;
        seg.sn = 0;
        seg.una = 0;
        seg.resendts = 0;
        seg.rto = 0;
        seg.fastack = 0;
        seg.xmit = 0;
        return seg;
    }

    private static void writeInt(byte[] buf, int idx, int value) {
        buf[idx] = (byte) (value >>> 24);
        buf[idx + 1] = (byte) (value >>> 16);
        buf[idx + 2] = (byte) (value >>> 8);
        buf[idx + 3] = (byte) value;
    }

    private static int readInt(byte[] buf, int idx) {
        return ((buf[idx] & 0xFF) << 24)
                | ((buf[idx + 1] & 0xFF) << 16)
                | ((buf[idx + 2] & 0xFF) << 8)
                | (buf[idx + 3] & 0xFF);
    }
}
