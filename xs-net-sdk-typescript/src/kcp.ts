// 自研 KCP：标准 KCP 可靠 UDP 协议，忠实移植 skywind3000/kcp 的 ikcp 算法。
// 线格式：一段一包，大端序 conv(4)+cmd(1)+frg(1)+wnd(2)+ts(4)+sn(4)+una(4)+len(4)+data。
// 无第三方依赖，仅依赖 ES2022 + BigInt 无关的标准 Number/位运算。

export const IKCP_RTO_NDL = 30;
export const IKCP_RTO_MIN = 100;
export const IKCP_RTO_DEF = 200;
export const IKCP_RTO_MAX = 60000;
export const IKCP_WND_SND = 32;
export const IKCP_WND_RCV = 128;
export const IKCP_MTU_DEF = 1400;
export const IKCP_ACK_FAST = 3;
export const IKCP_INTERVAL = 100;
export const IKCP_OVERHEAD = 24;
export const IKCP_DEADLINK = 20;
export const IKCP_THRESH_INIT = 2;
export const IKCP_THRESH_MIN = 2;
export const IKCP_PROBE_INIT = 7000;
export const IKCP_PROBE_LIMIT = 120000;
export const IKCP_CWND_INIT = 3;

export const IKCP_CMD_PUSH = 81;
export const IKCP_CMD_ACK = 82;
export const IKCP_CMD_WASK = 83;
export const IKCP_CMD_WINS = 84;

const IKCP_ASK_SEND = 1;
const IKCP_ASK_TELL = 2;

/** 计算 32 位无符号回绕差值：返回带符号的 (a - b)，用于比较序号的先后。 */
export function itimediff(a: number, b: number): number {
  return ((a >>> 0) - (b >>> 0)) | 0;
}

function ibound(lower: number, middle: number, upper: number): number {
  return Math.min(Math.max(lower, middle), upper);
}

function imax(a: number, b: number): number {
  return a > b ? a : b;
}

interface KcpSegment {
  conv: number;
  cmd: number;
  frg: number;
  wnd: number;
  ts: number;
  sn: number;
  una: number;
  /** 载荷副本（精准拷入当前 segment），绝不共享底层缓冲区。 */
  data: Uint8Array;
  /** 传输/重传控制字段（未命名段 flush 时才填充）。 */
  xmit: number;
  rto: number;
  fastack: number;
  resendts: number;
}

export interface KcpOptions {
  /** 会话标识；两端必须一致。默认取一个随机值。 */
  conv?: number;
  /** 输出回调：把一条 DATAGRAM 写出（底层丢给 UDP 发送）。 */
  sendDatagram: (data: Uint8Array) => void;
  /** 交付回调：按序交付一条完整的应用消息。 */
  onDeliver: (payload: Uint8Array) => void;
  /** 仅用于测试：初始发送序号，便于构造回绕场景。 */
  initialSndNxt?: number;
  /** 仅用于测试：初始接收序号，便于构造回绕场景。 */
  initialRcvNxt?: number;
}

export class Kcp {
  private readonly sendDatagram: (data: Uint8Array) => void;
  private readonly onDeliver: (payload: Uint8Array) => void;

  private readonly conv: number;
  private state: number; // states: IKCP_STATE_*
  private mtu: number;
  private mss: number;
  private updated: boolean;

  // 序号状态（32 位无符号回绕）
  private sndUna: number;
  private sndNxt: number;
  private rcvNxt: number;

  // RTT / RTO
  private rxRttval: number;
  private rxSrtt: number;
  private rxRto: number;
  private rxMinrto: number;

  // 窗口
  private sndWnd: number;
  private rcvWnd: number;
  private rmtWnd: number;
  private cwnd: number;
  private incr: number;
  private probe: number;
  private ssthresh: number;

  // 时间
  private current: number;
  private interval: number;
  private tsFlush: number;
  private xmit: number;

  // 快速重传 / 拥塞控制模式
  private nodelay: number;
  private fastresend: number;
  private nocwnd: boolean;
  private deadLink: number;

  // 探测
  private tsProbe: number;
  private probeWait: number;

  // 队列：snd_queue 待发送 / snd_buf 已发送未确认 / rcv_buf 乱序缓存 / rcv_queue 就绪待交付
  private sndQueue: KcpSegment[] = [];
  private sndBuf: KcpSegment[] = [];
  private rcvBuf: KcpSegment[] = [];
  private rcvQueue: KcpSegment[] = [];

  // 待聚合 ACK（扁平 [sn, ts] 对）
  private acklist: number[] = [];
  private ackCount = 0;

  // 输出组装缓冲
  private buffer: Uint8Array;

  public constructor(options: KcpOptions) {
    this.conv = (options.conv ?? ((Math.random() * 0xffffffff) >>> 0)) >>> 0;
    this.sendDatagram = options.sendDatagram;
    this.onDeliver = options.onDeliver;

    this.mtu = IKCP_MTU_DEF;
    this.mss = this.mtu - IKCP_OVERHEAD;
    this.state = 0;
    this.updated = false;

    this.sndUna = 0;
    this.sndNxt = (options.initialSndNxt ?? 0) >>> 0;
    this.rcvNxt = (options.initialRcvNxt ?? 0) >>> 0;

    this.rxRttval = 0;
    this.rxSrtt = 0;
    this.rxRto = IKCP_RTO_DEF;
    this.rxMinrto = IKCP_RTO_MIN;

    this.sndWnd = IKCP_WND_SND;
    this.rcvWnd = IKCP_WND_RCV;
    this.rmtWnd = IKCP_WND_RCV;
    this.cwnd = IKCP_THRESH_INIT;
    this.incr = 0;
    this.probe = 0;
    this.ssthresh = IKCP_THRESH_INIT;

    this.current = 0;
    this.interval = IKCP_INTERVAL;
    this.tsFlush = 0;
    this.xmit = 0;

    this.nodelay = 0;
    this.fastresend = 0;
    this.nocwnd = false;
    this.deadLink = IKCP_DEADLINK;

    this.tsProbe = 0;
    this.probeWait = 0;

    this.buffer = new Uint8Array(this.mtu + IKCP_OVERHEAD);
  }

  // ---- 配置接口 ----

  public setNodelay(nodelay: number, interval = IKCP_INTERVAL, resend = 0, nc = 0): void {
    if (nodelay >= 0) {
      this.nodelay = nodelay;
      if (nodelay) {
        this.rxMinrto = IKCP_RTO_NDL;
        this.interval = interval >= 0 ? interval : IKCP_INTERVAL;
      } else {
        this.rxMinrto = IKCP_RTO_MIN;
        this.interval = interval >= 0 ? interval : IKCP_INTERVAL;
      }
    }
    if (resend >= 0) this.fastresend = resend;
    if (nc >= 0) this.nocwnd = nc !== 0;
  }

  public setInterval(interval: number): void {
    if (interval > 0) this.interval = interval;
  }

  public setWndsize(sndwnd: number, rcvwnd: number): void {
    if (sndwnd > 0) {
      this.sndWnd = sndwnd;
    }
    if (rcvwnd > 0) {
      this.rcvWnd = Math.max(rcvwnd, IKCP_WND_RCV); // 至少为接收窗口下限，避免过大乱序
    }
  }

  public setMtu(mtu: number): void {
    if (mtu < 50 || mtu < IKCP_OVERHEAD) return;
    this.mtu = mtu;
    this.mss = this.mtu - IKCP_OVERHEAD;
    this.buffer = new Uint8Array(this.mtu + IKCP_OVERHEAD);
  }

  // ---- 对外操作 ----

  /** 发送一条完整应用消息。内部按 mtU 分片。 */
  public send(data: Uint8Array): void {
    if (data.length === 0) {
      // 允许空载荷（用于 auth 空回包等场景），发送一个 0 长度 PUSH 段。
      this.enqueueSegment(0, new Uint8Array(0));
      return;
    }
    const count = Math.floor((data.length + this.mss - 1) / this.mss);
    for (let i = 0; i < count; i++) {
      const offset = i * this.mss;
      const size = Math.min(this.mss, data.length - offset);
      this.enqueueSegment(count - i - 1, data.slice(offset, offset + size));
    }
  }

  /** 喂入一条从网络上收到的 DATAGRAM（可能包含多个段）。返回 0 成功，负值为错误。 */
  public input(data: Uint8Array): number {
    if (data.length < IKCP_OVERHEAD) return -1;
    let offset = 0;
    let flag = false;
    let maxack = 0;
    let latestTs = 0;

    while (data.length - offset >= IKCP_OVERHEAD) {
      const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
      const conv = view.getUint32(offset);
      const cmd = data[offset + 4] ?? 0;
      const frg = data[offset + 5] ?? 0;
      const wnd = view.getUint16(offset + 6);
      const ts = view.getUint32(offset + 8);
      const sn = view.getUint32(offset + 12);
      const una = view.getUint32(offset + 16);
      const len = view.getUint32(offset + 20);
      if (data.length - offset < IKCP_OVERHEAD + len) return -2;
      const payload = data.slice(offset + IKCP_OVERHEAD, offset + IKCP_OVERHEAD + len);
      offset += IKCP_OVERHEAD + len;

      if (conv !== this.conv) return -1;

      // 更新对端接收窗口
      this.rmtWnd = wnd;
      // 对端已确认的累计序号
      if (itimediff(this.sndUna, una) < 0) {
        this.sndUna = una;
      }

      if (cmd === IKCP_CMD_ACK) {
        const rtt = itimediff(this.current, ts);
        if (rtt >= 0) {
          if (this.rxSrtt === 0) {
            this.rxSrtt = rtt;
            this.rxRttval = rtt >> 1;
          } else {
            const delta = rtt > this.rxSrtt ? rtt - this.rxSrtt : this.rxSrtt - rtt;
            this.rxRttval = ((3 * this.rxRttval + delta) >> 2) | 0;
            this.rxSrtt = ((7 * this.rxSrtt + rtt) >> 3) | 0;
            if (this.rxSrtt < 1) this.rxSrtt = 1;
          }
          this.rxRto = ibound(this.rxMinrto, (this.rxSrtt | 0) + imax(1, this.rxRttval * 4), IKCP_RTO_MAX);
        }
        this.pushAck(sn, ts);

        // 从发送缓冲中移除已确认段，并做 AIMD 拥塞窗口调整。
        while (this.sndBuf.length > 0 && itimediff(this.sndBuf[0]!.sn, sn) <= 0) {
          const seg = this.sndBuf[0]!;
          const d = itimediff(seg.sn, sn);
          if (d === 0) {
            // 正是被确认的那一段：若其序号超前于当前累计(存在空洞/乱序确认)，触发前端段快速重传计数。
            if (!this.nocwnd) {
              if (this.cwnd < this.ssthresh) {
                // 慢启动
                this.cwnd = (this.cwnd + 1) | 0;
                this.incr = 0;
              } else {
                // 拥塞避免
                this.incr = (this.incr + this.mss) | 0;
                if (this.incr >= this.cwnd) {
                  this.cwnd = (this.cwnd + 1) | 0;
                  this.incr = 0;
                }
              }
              if (this.cwnd > this.rmtWnd) this.cwnd = this.rmtWnd;
            }
          } else {
            // 早于 sn 的段，说明存在乱序确认：给前一段累积 fastack 触发快速重传。
            if (this.sndBuf.length > 1) {
              const front = this.sndBuf[0]!;
              front.fastack = (front.fastack | 0) + 1;
              if (itimediff(sn, maxack) > 0) {
                maxack = sn;
                latestTs = ts;
              }
            }
          }
          this.sndBuf.shift();
        }
        flag = true;
        continue;
      }

      if (cmd === IKCP_CMD_PUSH) {
        if (itimediff(sn, (this.rcvNxt + this.rcvWnd) >>> 0) < 0) {
          this.pushAck(sn, ts);
          if (itimediff(sn, this.rcvNxt) >= 0) {
            const seg: KcpSegment = {
              conv,
              cmd,
              frg: frg,
              wnd: 0,
              ts,
              sn: sn,
              una: 0,
              data: payload,
              xmit: 0,
              rto: 0,
              fastack: 0,
              resendts: 0
            };
            this.insertRcvBuf(seg);
            // 将连续的段移入就绪队列
            while (this.rcvBuf.length > 0 && this.rcvBuf[0]!.sn === this.rcvNxt) {
              this.rcvNxt = (this.rcvNxt + 1) >>> 0;
              this.rcvQueue.push(this.rcvBuf.shift()!);
            }
          }
        }
        continue;
      }

      if (cmd === IKCP_CMD_WASK) {
        this.probe = (this.probe | IKCP_ASK_TELL) >>> 0;
      }

      // WINS / 其他命令：无额外处理
    }

    this.deliverReady();
    return 0;
  }

  /** 推进时间（时钟）。按 interval 触发 flush；首次调用立即 flush 以发出队列中的数据。 */
  public update(nowMs: number): void {
    const current = nowMs >>> 0;
    const slap = itimediff(current, this.current);
    if (slap >= 10000 || slap < -10000) {
      this.current = current;
      this.tsFlush = (current + this.interval) >>> 0;
      return;
    }
    if (!this.updated) {
      this.updated = true;
      this.current = current;
      this.flush();
      this.tsFlush = (current + this.interval) >>> 0;
      return;
    }
    if (itimediff(current, this.tsFlush) >= 0) {
      this.current = current;
      this.flush();
      this.tsFlush = (current + this.interval) >>> 0;
    }
  }

  /** 强制刷新：把待发送数据、ACK 与探测包组装成 DATAGRAM 输出。 */
  public flush(): void {
    if (!this.updated) return;
    const current = this.current;
    const buffer = this.buffer;
    let ptr = 0;

    // 段模板：仅共用 conv/cmd/wnd/una/len/… 头部可变字段
    const segWnd = this.wndUnused();
    const segUna = this.rcvNxt;

    // 刷新 ACK
    for (let i = 0; i < this.ackCount; i++) {
      if (ptr + IKCP_OVERHEAD > this.mtu) {
        this.outputBuffer(buffer, ptr);
        ptr = 0;
      }
      ptr = this.encodeSegment(
        buffer,
        ptr,
        this.conv,
        IKCP_CMD_ACK,
        0,
        segWnd,
        this.acklist[i * 2]!,
        this.acklist[i * 2 + 1]!,
        segUna,
        IKCP_OVERHEAD,
        undefined
      );
    }
    this.ackCount = 0;

    // 窗口探测
    if (this.rmtWnd === 0) {
      if (this.probeWait === 0) {
        this.probeWait = IKCP_PROBE_INIT;
        this.tsProbe = (this.current + this.probeWait) >>> 0;
      } else if (itimediff(this.current, this.tsProbe) >= 0) {
        if (this.probeWait < IKCP_PROBE_INIT) this.probeWait = IKCP_PROBE_INIT;
        this.probeWait = (this.probeWait + (this.probeWait >> 1)) | 0;
        if (this.probeWait > IKCP_PROBE_LIMIT) this.probeWait = IKCP_PROBE_LIMIT;
        this.tsProbe = (this.current + this.probeWait) >>> 0;
        this.probe = (this.probe | IKCP_ASK_SEND) >>> 0;
      }
    } else {
      this.tsProbe = 0;
      this.probeWait = 0;
    }

    if ((this.probe & IKCP_ASK_SEND) !== 0) {
      if (ptr + IKCP_OVERHEAD > this.mtu) {
        this.outputBuffer(buffer, ptr);
        ptr = 0;
      }
      ptr = this.encodeSegment(buffer, ptr, this.conv, IKCP_CMD_WASK, 0, segWnd, 0, 0, segUna, IKCP_OVERHEAD, undefined);
    }
    if ((this.probe & IKCP_ASK_TELL) !== 0) {
      if (ptr + IKCP_OVERHEAD > this.mtu) {
        this.outputBuffer(buffer, ptr);
        ptr = 0;
      }
      ptr = this.encodeSegment(buffer, ptr, this.conv, IKCP_CMD_WINS, 0, segWnd, 0, 0, segUna, IKCP_OVERHEAD, undefined);
    }
    this.probe = 0;

    // 发送窗口
    let cwnd = this.sndWnd;
    if (!this.nocwnd) cwnd = Math.min(cwnd, this.cwnd);
    if (cwnd < 1) cwnd = 1;
    if (this.rmtWnd < cwnd) cwnd = this.rmtWnd;

    const resent = this.fastresend > 0 ? this.fastresend : 0x7fffffff;
    const rtomin = this.nodelay === 0 ? this.rxRto >> 3 : 0;
    let retransmitted = 0;
    let fastRetransmitted = 0;

    // 发送队列 -> 发送缓冲
    while (itimediff(this.sndNxt, (this.sndUna + cwnd) >>> 0) < 0) {
      if (this.sndQueue.length === 0) break;
      const seg = this.sndQueue.shift()!;
      seg.wnd = segWnd;
      seg.sn = this.sndNxt;
      this.sndNxt = (this.sndNxt + 1) >>> 0;
      seg.ts = current;
      seg.una = segUna;
      seg.resendts = current;
      seg.rto = this.rxRto;
      seg.fastack = 0;
      seg.xmit = 0;
      this.sndBuf.push(seg);
    }

    // 从发送缓冲输出（含超时重传与快速重传）
    for (let i = 0; i < this.sndBuf.length; i++) {
      const seg = this.sndBuf[i]!;
      let needsend = false;
      if (seg.xmit === 0) {
        needsend = true;
        seg.xmit = 1;
        seg.rto = this.rxRto;
        seg.resendts = (current + seg.rto + rtomin) >>> 0;
      } else if (itimediff(current, seg.resendts) >= 0) {
        needsend = true;
        seg.xmit++;
        this.xmit++;
        if (this.nodelay === 0) {
          seg.rto = (seg.rto + (this.rxRto >> 1)) | 0;
        } else {
          seg.rto = (seg.rto + (this.rxRto >> 1)) | 0;
        }
        seg.resendts = (current + seg.rto) >>> 0;
        retransmitted++;
      } else if (seg.fastack >= resent) {
        needsend = true;
        seg.xmit++;
        seg.fastack = 0;
        seg.resendts = (current + seg.rto) >>> 0;
        fastRetransmitted++;
      }

      if (needsend) {
        seg.ts = current;
        seg.wnd = segWnd;
        seg.una = segUna;
        const need = IKCP_OVERHEAD + seg.data.length;
        if (ptr + need > this.mtu) {
          this.outputBuffer(buffer, ptr);
          ptr = 0;
        }
        ptr = this.encodeSegment(buffer, ptr, seg.conv, seg.cmd, seg.frg, seg.wnd, seg.ts, seg.sn, seg.una, need, seg.data);
        if (seg.xmit >= this.deadLink) {
          this.state = 0xffffffff;
        }
      }
    }

    // 剩余输出
    if (ptr > 0) this.outputBuffer(buffer, ptr);

    // 拥塞控制（AIMD / 快速恢复）
    if (!this.nocwnd) {
      let cc = cwnd;
      if (retransmitted > 0) {
        this.ssthresh = Math.max(this.cwnd >> 1, IKCP_THRESH_MIN);
        this.cwnd = 1;
        this.incr = 0;
      }
      if (fastRetransmitted > 0) {
        this.ssthresh = Math.max(this.cwnd >> 1, IKCP_THRESH_MIN);
        this.cwnd = this.ssthresh + fastRetransmitted * 3;
        this.incr = 0;
      }
      if (this.cwnd < 1) this.cwnd = 1;
      if (this.cwnd > this.rmtWnd) this.cwnd = this.rmtWnd;
      if (this.cwnd > this.sndWnd) this.cwnd = this.sndWnd;
      void cc;
    }

    this.tsFlush = (current + this.interval) >>> 0;
  }

  // ---- 内部工具 ----

  /** 当前接收窗口剩余可用量（把空闲窗口告诉对端）。 */
  private wndUnused(): number {
    const used = this.rcvQueue.length;
    if (used < this.rcvWnd) return this.rcvWnd - used;
    return 0;
  }

  private enqueueSegment(frg: number, data: Uint8Array): void {
    this.sndQueue.push({
      conv: this.conv,
      cmd: IKCP_CMD_PUSH,
      frg,
      wnd: 0,
      ts: 0,
      sn: 0,
      una: 0,
      data,
      xmit: 0,
      rto: 0,
      fastack: 0,
      resendts: 0
    });
  }

  private pushAck(sn: number, ts: number): void {
    const index = this.ackCount * 2;
    if (index >= this.acklist.length) {
      this.acklist.length = index + 2;
    }
    this.acklist[index] = sn >>> 0;
    this.acklist[index + 1] = ts >>> 0;
    this.ackCount++;
  }

  private insertRcvBuf(seg: KcpSegment): void {
    let i = 0;
    // 找到第一个 sn 大于等于新段的位置（rcv_nxt <= sn，用回绕比较排序）
    while (i < this.rcvBuf.length && itimediff(this.rcvBuf[i]!.sn, seg.sn) < 0) i++;
    this.rcvBuf.splice(i, 0, seg);
  }

  /** 把 rcv_queue 中已连续、可完整的应用消息（含分片链）交付出去。 */
  private deliverReady(): void {
    while (this.rcvQueue.length > 0) {
      const first = this.rcvQueue[0]!;
      if (first.frg === 0) {
        this.rcvQueue.shift();
        this.onDeliver(first.data.slice(0, first.data.length));
        continue;
      }
      // 分片链：frg 递减到 0，需完整链才能交付
      let total = 0;
      let n = 0;
      let complete = false;
      for (const seg of this.rcvQueue) {
        total += seg.data.length;
        n++;
        if (seg.frg === 0) {
          complete = true;
          break;
        }
      }
      if (!complete) break;
      const out = new Uint8Array(total);
      let o = 0;
      for (let k = 0; k < n; k++) {
        const seg = this.rcvQueue[k]!;
        out.set(seg.data, o);
        o += seg.data.length;
      }
      this.rcvQueue.splice(0, n);
      this.onDeliver(out);
    }
  }

  /** 把一段编码进输出缓冲，返回新的写入偏移。 */
  private encodeSegment(
    buffer: Uint8Array,
    offset: number,
    conv: number,
    cmd: number,
    frg: number,
    wnd: number,
    ts: number,
    sn: number,
    una: number,
    headerEnd: number,
    data: Uint8Array | undefined
  ): number {
    const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
    view.setUint32(offset, conv >>> 0);
    buffer[offset + 4] = cmd;
    buffer[offset + 5] = frg & 0xff;
    view.setUint16(offset + 6, wnd & 0xffff);
    view.setUint32(offset + 8, ts >>> 0);
    view.setUint32(offset + 12, sn >>> 0);
    view.setUint32(offset + 16, una >>> 0);
    const len = headerEnd - IKCP_OVERHEAD;
    view.setUint32(offset + 20, len >>> 0);
    if (data && data.length > 0) {
      buffer.set(data, offset + IKCP_OVERHEAD);
    }
    return offset + headerEnd;
  }

  private outputBuffer(buffer: Uint8Array, length: number): void {
    // 精准复制副本，避免共享底层缓冲给上层（防止误改后续段）。
    const out = new Uint8Array(buffer.buffer.slice(0, length));
    this.sendDatagram(out);
  }

  // ---- 只读观测（便于测试与诊断） ----

  public get currentTs(): number {
    return this.current;
  }

  public get sndNxtVal(): number {
    return this.sndNxt;
  }

  public get rcvNxtVal(): number {
    return this.rcvNxt;
  }

  public get outstanding(): number {
    return this.sndBuf.length;
  }

  public get waitSnd(): number {
    return this.sndQueue.length + this.sndBuf.length;
  }

  public get rtoo(): number {
    return this.rxRto;
  }

  public get cwndVal(): number {
    return this.cwnd;
  }

  public get dead(): boolean {
    return this.state === 0xffffffff;
  }
}