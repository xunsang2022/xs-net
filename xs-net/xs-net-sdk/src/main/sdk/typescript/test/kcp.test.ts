import { describe, expect, it } from "vitest";
import { IKCP_CMD_PUSH, itimediff, Kcp } from "../src/kcp.js";

/** 手工构造一条 KCP PUSH DATAGRAM（标准线格式）。 */
function pushPacket(conv: number, frg: number, wnd: number, ts: number, sn: number, una: number, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(24 + data.length);
  const v = new DataView(out.buffer);
  v.setUint32(0, conv >>> 0);
  out[4] = IKCP_CMD_PUSH;
  out[5] = frg & 0xff;
  v.setUint16(6, wnd & 0xffff);
  v.setUint32(8, ts >>> 0);
  v.setUint32(12, sn >>> 0);
  v.setUint32(16, una >>> 0);
  v.setUint32(20, data.length >>> 0);
  out.set(data, 24);
  return out;
}

/** 双向虚拟链路：a 产出 -> b 输入，b 产出 -> a 输入。 */
class VirtualLink {
  public readonly a: Kcp;
  public readonly b: Kcp;
  public readonly aToB: Uint8Array[] = [];
  public readonly bToA: Uint8Array[] = [];
  public readonly aDelivered: Uint8Array[] = [];
  public readonly bDelivered: Uint8Array[] = [];
  public dropA2B = false;
  private now = 0;

  public constructor(aInitialSndNxt?: number) {
    this.a = new Kcp({
      conv: 1,
      sendDatagram: (d) => this.aToB.push(d),
      onDeliver: (p) => this.aDelivered.push(p),
      initialSndNxt: aInitialSndNxt
    });
    // 发送端序号回绕时，接收端初始接收序号应同步播种到同一水位。
    this.b = new Kcp({
      conv: 1,
      sendDatagram: (d) => this.bToA.push(d),
      onDeliver: (p) => this.bDelivered.push(p),
      initialRcvNxt: aInitialSndNxt
    });
    for (const k of [this.a, this.b]) {
      k.setNodelay(1, 10, 0, 1);
      k.setInterval(10);
      k.setWndsize(64, 64);
    }
  }

  public step(ms: number): void {
    for (let i = 0; i < ms; i++) {
      this.now += 1;
      this.a.update(this.now);
      this.b.update(this.now);
      if (!this.dropA2B) {
        const toB = this.aToB.splice(0);
        for (const d of toB) this.b.input(d);
      }
      const toA = this.bToA.splice(0);
      for (const d of toA) this.a.input(d);
    }
  }
}

describe("Kcp 回绕比较", () => {
  it("wraps 后的序号判新：0 比 0xFFFFFFFE 新", () => {
    expect(itimediff(1, 0xfffffffe)).toBeGreaterThan(0);
    expect(itimediff(0, 0xfffffff8)).toBeGreaterThan(0);
    expect(itimediff(0xfffffffe, 1)).toBeLessThan(0);
  });
});

describe("Kcp 乱序重排", () => {
  it("按乱序到达的段仍保持交付顺序", () => {
    const delivered: Uint8Array[] = [];
    const b = new Kcp({ conv: 7, sendDatagram: () => undefined, onDeliver: (p) => delivered.push(p) });
    // sn 先到 2，再回 1，空档 0 最后到 -> 应该按 0,1,2 交付
    b.input(pushPacket(7, 0, 128, 1, 2, 0, new Uint8Array([22])));
    b.input(pushPacket(7, 0, 128, 1, 1, 0, new Uint8Array([11])));
    expect(delivered.length).toBe(0);
    b.input(pushPacket(7, 0, 128, 1, 0, 0, new Uint8Array([0])));
    expect(delivered.map((d) => d[0])).toEqual([0, 11, 22]);
  });
});

describe("Kcp 分片合并", () => {
  it("把大于 MSS 的消息按 frg 链重组并交付一条完整消息", () => {
    const delivered: Uint8Array[] = [];
    const b = new Kcp({ conv: 1, sendDatagram: () => undefined, onDeliver: (p) => delivered.push(p) });
    const message = new Uint8Array(3000);
    for (let i = 0; i < message.length; i++) message[i] = i & 0xff;
    const mss = 1400 - 24;
    const count = Math.ceil(message.length / mss);
    // 反序到达碎片，验证乱序 + 分片并存
    for (let i = count - 1; i >= 0; i--) {
      const off = i * mss;
      const part = message.slice(off, Math.min(message.length, off + mss));
      b.input(pushPacket(1, count - i - 1, 128, 1, i, 0, part));
    }
    expect(delivered).toHaveLength(1);
    expect(delivered[0]).toEqual(message);
  });
});

describe("Kcp RTO 超时重传", () => {
  it("对端未确认时会在 resendts 后重发包，重传次数随流逝时间增加", () => {
    const link = new VirtualLink();
    link.dropA2B = true; // 丢弃所有 B 方向数据包，B 永不确定
    link.a.send(new TextEncoder().encode("hello-kcp"));
    link.step(1);
    const baseline = link.aToB.length;
    expect(baseline).toBeGreaterThanOrEqual(1);
    link.step(1500);
    expect(link.aToB.length).toBeGreaterThan(baseline);
  });
});

describe("Kcp 双向可靠传输", () => {
  it("一条虚拟链路上 a->b 按序送达并收到回包", () => {
    const link = new VirtualLink();
    link.a.send(new TextEncoder().encode("ping"));
    link.step(20);
    expect(link.bDelivered.map((d) => new TextDecoder().decode(d))).toEqual(["ping"]);
    // b 回一条，继续推进时钟（至少一个 interval，保证 flush），a 收到
    link.b.send(new TextEncoder().encode("pong"));
    link.step(40);
    expect(link.aDelivered.map((d) => new TextDecoder().decode(d))).toEqual(["pong"]);
  });

  it("发送序号回绕后仍能按序交付", () => {
    const link = new VirtualLink(0xfffffff8);
    for (let i = 0; i < 9; i++) {
      link.a.send(new Uint8Array([i]));
      link.step(20);
    }
    expect(link.bDelivered.map((d) => d[0])).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8]);
  });
});