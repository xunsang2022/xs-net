#pragma once

// Standard KCP reliable-UDP transport.
//
// This is a from-scratch C++ port that faithfully preserves the algorithm of
// skywind3000/kcp (public MIT AUTH algorithm, ikcp.c / ikcp.h), with NO third
// party dependency.  Only the wire byte order is made explicit big-endian
// (matching the rest of xs-net), which is transparent to the algorithm.
//
// Wire format (one segment per frame, big-endian):
//   conv(4) cmd(1) frg(1) wnd(2) ts(4) sn(4) una(4) len(4) data
//   cmd: 81=PUSH 82=ACK 83=WASK 84=WINS
//
// Preserved ikcp semantics: RTO / MIN_RTO / dead link / fast retransmit
// threshold (fastresend) / AIMD congestion window (cwnd+ssthresh+incr) /
// adaptive RTO (srtt+rttval+rto, RFC 6298 style) / unsigned 32-bit sequence
// wrap-around compared as int32 deltas / fragment merge (frg) / the four
// queues snd_queue+rcv_queue+snd_buf+rcv_buf (std::deque).

#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <utility>
#include <vector>

#include <algorithm>

namespace xsnet {

namespace kcp_detail {

// big-endian codec helpers (no misaligned access : byte-wise).
inline std::uint16_t rd16(const std::uint8_t* p) {
    return static_cast<std::uint16_t>((static_cast<std::uint16_t>(p[0]) << 8U) | static_cast<std::uint16_t>(p[1]));
}
inline std::uint32_t rd32(const std::uint8_t* p) {
    return (static_cast<std::uint32_t>(p[0]) << 24U)
        | (static_cast<std::uint32_t>(p[1]) << 16U)
        | (static_cast<std::uint32_t>(p[2]) << 8U)
        | static_cast<std::uint32_t>(p[3]);
}
inline std::uint8_t* wr8(std::uint8_t* p, std::uint8_t c) {
    p[0] = c;
    return p + 1;
}
inline std::uint8_t* wr16(std::uint8_t* p, std::uint16_t w) {
    p[0] = static_cast<std::uint8_t>(w >> 8U);
    p[1] = static_cast<std::uint8_t>(w);
    return p + 2;
}
inline std::uint8_t* wr32(std::uint8_t* p, std::uint32_t l) {
    p[0] = static_cast<std::uint8_t>(l >> 24U);
    p[1] = static_cast<std::uint8_t>(l >> 16U);
    p[2] = static_cast<std::uint8_t>(l >> 8U);
    p[3] = static_cast<std::uint8_t>(l);
    return p + 4;
}

}  // namespace kcp_detail

class Kcp {
public:
    using DatagramSender = std::function<void(const std::vector<std::uint8_t>&)>;
    using PayloadHandler = std::function<void(const std::vector<std::uint8_t>&)>;

    // Unsigned 32-bit sequence-delta comparator (wrap-around safe).
    static std::int32_t diff(std::uint32_t later, std::uint32_t earlier) {
        return static_cast<std::int32_t>(later - earlier);
    }

    Kcp(std::uint32_t conv, DatagramSender sender, PayloadHandler deliver)
        : conv_(conv), sender_(std::move(sender)), deliver_(std::move(deliver)) {
    }

    // Split an application message into KCP fragments and queue it for send.
    // Returns bytes queued, or a negative error (mirrors ikcp_send).
    int send(const std::vector<std::uint8_t>& buffer) {
        int len = static_cast<int>(buffer.size());
        if (len < 0) return -1;

        int count = (len <= static_cast<int>(mss_)) ? 1 : (len + static_cast<int>(mss_) - 1) / static_cast<int>(mss_);
        if (count >= static_cast<int>(kWndRcv)) return -2;
        if (count == 0) count = 1;

        int sent = 0;
        for (int i = 0; i < count; i++) {
            const int size = len > static_cast<int>(mss_) ? static_cast<int>(mss_) : len;
            Segment seg;
            if (size > 0) {
                seg.data.assign(buffer.begin() + static_cast<std::ptrdiff_t>(sent),
                                buffer.begin() + static_cast<std::ptrdiff_t>(sent) + size);
            }
            seg.len = static_cast<std::uint32_t>(size);
            seg.frg = static_cast<std::uint32_t>(count - i - 1);  // stream mode off
            snd_queue_.push_back(std::move(seg));
            len -= size;
            sent += size;
        }
        return sent;
    }

    // Feed received datagram fragment(s) into the session.
    int input(const std::uint8_t* data, std::size_t size) {
        const std::uint32_t prev_una = snd_una_;
        const std::size_t prev_nsnd_buf = snd_buf_.size();
        ackedlen_ = 0;

        if (data == nullptr || size < kOverhead) return -1;

        std::size_t pos = 0;
        int flag = 0;
        std::uint32_t maxack = 0, latest_ts = 0;

        while (size - pos >= kOverhead) {
            const std::uint8_t* p = data + pos;
            const std::uint32_t conv = kcp_detail::rd32(p);
            p += 4;
            if (conv != conv_) return -1;
            const std::uint8_t cmd = *p++;
            const std::uint8_t frg = *p++;
            const std::uint16_t wnd = kcp_detail::rd16(p);
            p += 2;
            const std::uint32_t ts = kcp_detail::rd32(p);
            p += 4;
            const std::uint32_t sn = kcp_detail::rd32(p);
            p += 4;
            const std::uint32_t una = kcp_detail::rd32(p);
            p += 4;
            const std::uint32_t len = kcp_detail::rd32(p);
            p += 4;

            const std::size_t header_len = static_cast<std::size_t>(p - (data + pos));
            if (size - pos < header_len + len) return -2;

            if (cmd != kCmdPush && cmd != kCmdAck && cmd != kCmdWask && cmd != kCmdWins) return -3;

            rmt_wnd_ = wnd;
            parse_una(una);
            shrink_buf();

            if (cmd == kCmdAck) {
                if (diff(current_, ts) >= 0) update_ack(diff(current_, ts));
                parse_ack(sn);
                shrink_buf();
                if (flag == 0) {
                    flag = 1;
                    maxack = sn;
                    latest_ts = ts;
                } else {
                    if (diff(sn, maxack) > 0) {
                        if (diff(ts, latest_ts) > 0) {  // IKCP_FASTACK_CONSERVE
                            maxack = sn;
                            latest_ts = ts;
                        }
                    }
                }
            } else if (cmd == kCmdPush) {
                if (diff(sn, rcv_nxt_ + rcv_wnd_) < 0) {
                    push_ack(sn, ts);
                    if (diff(sn, rcv_nxt_) >= 0) {
                        Segment seg;
                        seg.conv = conv;
                        seg.cmd = cmd;
                        seg.frg = frg;
                        seg.wnd = wnd;
                        seg.ts = ts;
                        seg.sn = sn;
                        seg.una = una;
                        seg.len = len;
                        if (len > 0) seg.data.assign(p, p + len);
                        parse_data(seg);
                    }
                }
            } else if (cmd == kCmdWask) {
                probe_ |= kAskTell;
            } else if (cmd == kCmdWins) {
                // window size telemetry, nothing to do here
            } else {
                return -3;
            }

            pos += header_len + len;
        }

        if (flag != 0) parse_fastack(maxack, latest_ts);

        if (diff(snd_una_, prev_una) > 0) {
            const std::uint32_t acked_segs = snd_una_ - prev_una;
            const std::uint32_t prior_in_flight = static_cast<std::uint32_t>(prev_nsnd_buf);
            (void)acked_segs;
            (void)prior_in_flight;
            if (cwnd_ < rmt_wnd_) {
                const std::uint32_t mss = mss_;
                if (cwnd_ < ssthresh_) {
                    cwnd_++;
                    incr_ += mss;
                } else {
                    if (incr_ < mss) incr_ = mss;
                    incr_ += (mss * mss) / incr_ + (mss / 16);
                    if ((cwnd_ + 1) * mss <= incr_) cwnd_ = (incr_ + mss - 1) / ((mss > 0) ? mss : 1);
                }
                if (cwnd_ > rmt_wnd_) {
                    cwnd_ = rmt_wnd_;
                    incr_ = rmt_wnd_ * mss;
                }
            }
        }

        drain();
        return 0;
    }

    // Advance session time; perform flushing. 'current' is in milliseconds.
    void update(std::uint32_t current) {
        current_ = current;
        if (updated_ == 0) {
            updated_ = 1;
            ts_flush_ = current_;
        }
        std::int32_t slap = diff(current_, ts_flush_);
        if (slap >= 10000 || slap < -10000) {
            ts_flush_ = current_;
            slap = 0;
        }
        if (slap >= 0) {
            ts_flush_ += interval_;
            if (diff(current_, ts_flush_) >= 0) ts_flush_ = current_ + interval_;
            do_flush();
        }
    }

    // Returns the next time (ms) update() should be called at the latest.
    std::uint32_t check(std::uint32_t current) const {
        if (updated_ == 0) return current;
        if (diff(current, ts_flush_) >= 10000 || diff(current, ts_flush_) < -10000) {
            if (diff(current, ts_flush_) >= 0) return current;  // constrained path below handles the normal case
        }
        if (diff(current, ts_flush_) >= 0) return current;

        std::int32_t tm_flush = diff(ts_flush_, current);
        std::int32_t tm_packet = 0x7fffffff;
        for (const auto& seg : snd_buf_) {
            const std::int32_t d = diff(seg.resendts, current);
            if (d <= 0) return current;
            if (d < tm_packet) tm_packet = d;
        }
        std::uint32_t minimal = static_cast<std::uint32_t>(tm_packet < tm_flush ? tm_packet : tm_flush);
        if (minimal >= interval_) minimal = interval_;
        return current + minimal;
    }

    void set_nodelay(int nodelay, int interval = -1, int resend = -1, int nc = -1) {
        if (nodelay >= 0) {
            nodelay_ = nodelay;
            rx_minrto_ = nodelay ? kRtoNdl : kRtoMin;
        }
        if (interval >= 0) set_interval(interval);
        if (resend >= 0) fastresend_ = resend;
        if (nc >= 0) nocwnd_ = nc;
    }

    void set_interval(int interval) {
        if (interval > 5000) interval = 5000;
        else if (interval < 10) interval = 10;
        interval_ = interval;
    }

    void set_wndsize(int sndwnd, int rcvwnd) {
        if (sndwnd > 0) snd_wnd_ = sndwnd;
        if (rcvwnd > 0) rcv_wnd_ = std::max<std::uint32_t>(rcvwnd, kWndRcv);  // must >= max fragment size
    }

    int set_mtu(int mtu) {
        if (mtu < 50 || mtu < static_cast<int>(kOverhead)) return -1;
        mtu_ = mtu;
        mss_ = mtu_ - kOverhead;
        return 0;
    }

    [[nodiscard]] std::size_t waitsnd() const { return snd_buf_.size() + snd_queue_.size(); }
    [[nodiscard]] std::int32_t state() const { return static_cast<std::int32_t>(state_); }
    [[nodiscard]] std::uint32_t conv() const { return conv_; }

private:
    //----------------------------------------------------------------- constants
    static constexpr std::uint32_t kRtoNdl = 30;
    static constexpr std::uint32_t kRtoMin = 100;
    static constexpr std::uint32_t kRtoDef = 200;
    static constexpr std::uint32_t kRtoMax = 60000;
    static constexpr std::uint32_t kCmdPush = 81;
    static constexpr std::uint32_t kCmdAck = 82;
    static constexpr std::uint32_t kCmdWask = 83;
    static constexpr std::uint32_t kCmdWins = 84;
    static constexpr std::uint32_t kAskSend = 1;
    static constexpr std::uint32_t kAskTell = 2;
    static constexpr std::uint32_t kWndSnd = 32;
    static constexpr std::uint32_t kWndRcv = 128;
    static constexpr std::uint32_t kMtuDef = 1400;
    static constexpr std::uint32_t kInterval = 100;
    static constexpr std::uint32_t kOverhead = 24;
    static constexpr std::uint32_t kDeadlink = 20;
    static constexpr std::uint32_t kThresholdInit = 2;
    static constexpr std::uint32_t kThresholdMin = 2;
    static constexpr std::uint32_t kProbeInit = 5000;
    static constexpr std::uint32_t kProbeLimit = 120000;
    static constexpr std::uint32_t kFastAckLimit = 5;

    struct Segment {
        std::uint32_t conv = 0;
        std::uint32_t cmd = kCmdPush;
        std::uint32_t frg = 0;
        std::uint32_t wnd = 0;
        std::uint32_t ts = 0;
        std::uint32_t sn = 0;
        std::uint32_t una = 0;
        std::uint32_t len = 0;
        std::uint32_t resendts = 0;
        std::uint32_t rto = 0;
        std::uint32_t fastack = 0;
        std::uint32_t xmit = 0;
        std::vector<std::uint8_t> data;
    };

    std::uint32_t wnd_unused() const {
        return (rcv_queue_.size() < rcv_wnd_) ? rcv_wnd_ - static_cast<std::uint32_t>(rcv_queue_.size()) : 0;
    }

    void do_flush() {
        if (updated_ == 0) {
            updated_ = 1;
            ts_flush_ = current_;
        }

        const std::uint32_t prior_cwnd = cwnd_;

        Segment seg;
        seg.conv = conv_;
        seg.cmd = kCmdAck;
        seg.frg = 0;
        seg.wnd = wnd_unused();
        seg.una = rcv_nxt_;
        seg.len = 0;
        seg.sn = 0;
        seg.ts = 0;

        // flush acknowledges
        const std::size_t ack_count = acklist_.size();
        for (std::size_t i = 0; i < ack_count; i++) {
            if (out_.size() + kOverhead > mtu_) flush_out();
            seg.sn = acklist_[i].first;
            seg.ts = acklist_[i].second;
            append_seg(seg);
        }
        acklist_.clear();

        // probe window size when remote window is zero
        if (rmt_wnd_ == 0) {
            if (probe_wait_ == 0) {
                probe_wait_ = kProbeInit;
                ts_probe_ = current_ + probe_wait_;
            } else {
                if (diff(current_, ts_probe_) >= 0) {
                    if (probe_wait_ < kProbeInit) probe_wait_ = kProbeInit;
                    probe_wait_ += probe_wait_ / 2;
                    if (probe_wait_ > kProbeLimit) probe_wait_ = kProbeLimit;
                    ts_probe_ = current_ + probe_wait_;
                    probe_ |= kAskSend;
                }
            }
        } else {
            ts_probe_ = 0;
            probe_wait_ = 0;
        }

        if (probe_ & kAskSend) {
            seg.cmd = kCmdWask;
            if (out_.size() + kOverhead > mtu_) flush_out();
            append_seg(seg);
        }
        if (probe_ & kAskTell) {
            seg.cmd = kCmdWins;
            if (out_.size() + kOverhead > mtu_) flush_out();
            append_seg(seg);
        }
        probe_ = 0;

        // calculate window size
        std::uint32_t cwnd = std::min<std::uint32_t>(snd_wnd_, rmt_wnd_);
        if (nocwnd_ == 0) cwnd = std::min<std::uint32_t>(cwnd_, cwnd);

        // move data from snd_queue -> snd_buf
        while (diff(snd_nxt_, snd_una_ + cwnd) < 0) {
            if (snd_queue_.empty()) break;
            Segment newseg = std::move(snd_queue_.front());
            snd_queue_.pop_front();
            newseg.conv = conv_;
            newseg.cmd = kCmdPush;
            newseg.wnd = seg.wnd;
            newseg.ts = current_;
            newseg.sn = snd_nxt_++;
            newseg.una = rcv_nxt_;
            newseg.resendts = current_;
            newseg.rto = rx_rto_;
            newseg.fastack = 0;
            newseg.xmit = 0;
            snd_buf_.push_back(std::move(newseg));
        }

        const std::uint32_t resent = (fastresend_ > 0) ? static_cast<std::uint32_t>(fastresend_) : 0xffffffffU;
        const std::uint32_t rtomin = (nodelay_ == 0) ? (rx_rto_ >> 3) : 0;

        int change = 0;
        int lost = 0;

        // flush data segments (retransmission + new sends)
        for (auto& segment : snd_buf_) {
            int needsend = 0;
            if (segment.xmit == 0) {
                needsend = 1;
                segment.xmit++;
                segment.rto = rx_rto_;
                segment.resendts = current_ + segment.rto + rtomin;
            } else if (diff(current_, segment.resendts) >= 0) {
                needsend = 1;
                segment.xmit++;
                xmit_++;
                if (nodelay_ == 0) {
                    segment.rto += std::max<std::uint32_t>(static_cast<std::uint32_t>(segment.rto),
                                                           static_cast<std::uint32_t>(rx_rto_));
                } else {
                    const std::int32_t step =
                        (nodelay_ < 2) ? static_cast<std::int32_t>(segment.rto) : static_cast<std::int32_t>(rx_rto_);
                    segment.rto += static_cast<std::uint32_t>(step / 2);
                }
                segment.resendts = current_ + segment.rto;
                lost = 1;
            } else if (segment.fastack >= resent) {
                if (static_cast<int>(segment.xmit) <= static_cast<int>(fastlimit_) || fastlimit_ <= 0) {
                    needsend = 1;
                    segment.xmit++;
                    segment.fastack = 0;
                    segment.resendts = current_ + segment.rto;
                    change++;
                }
            }

            if (needsend) {
                segment.ts = current_;
                segment.wnd = seg.wnd;
                segment.una = rcv_nxt_;

                const std::size_t need = kOverhead + segment.data.size();
                if (out_.size() + need > mtu_) flush_out();

                append_seg(segment);
                out_.insert(out_.end(), segment.data.begin(), segment.data.end());

                if (segment.xmit >= dead_link_) state_ = static_cast<std::uint32_t>(-1);
            }
        }

        flush_out();

        if (change) {
            const std::uint32_t inflight = snd_nxt_ - snd_una_;
            ssthresh_ = inflight / 2;
            if (ssthresh_ < kThresholdMin) ssthresh_ = kThresholdMin;
            cwnd_ = ssthresh_ + resent;
            incr_ = cwnd_ * mss_;
        }
        if (lost) {
            ssthresh_ = prior_cwnd / 2;
            if (ssthresh_ < kThresholdMin) ssthresh_ = kThresholdMin;
            cwnd_ = 1;
            incr_ = mss_;
        }
        if (cwnd_ < 1) {
            cwnd_ = 1;
            incr_ = mss_;
        }
    }

    void flush_out() {
        if (!out_.empty()) {
            sender_(out_);
            out_.clear();
        }
    }

    void append_seg(const Segment& seg) {
        std::uint8_t tmp[24];
        std::uint8_t* p = tmp;
        p = kcp_detail::wr32(p, seg.conv);
        p = kcp_detail::wr8(p, static_cast<std::uint8_t>(seg.cmd));
        p = kcp_detail::wr8(p, static_cast<std::uint8_t>(seg.frg));
        p = kcp_detail::wr16(p, static_cast<std::uint16_t>(seg.wnd));
        p = kcp_detail::wr32(p, seg.ts);
        p = kcp_detail::wr32(p, seg.sn);
        p = kcp_detail::wr32(p, seg.una);
        p = kcp_detail::wr32(p, seg.len);
        out_.insert(out_.end(), tmp, tmp + 24);
    }

    void push_ack(std::uint32_t sn, std::uint32_t ts) { acklist_.push_back({sn, ts}); }

    void parse_una(std::uint32_t una) {
        while (!snd_buf_.empty() && diff(una, snd_buf_.front().sn) > 0) {
            ackedlen_ += snd_buf_.front().len;
            snd_buf_.pop_front();
        }
    }

    void shrink_buf() { snd_una_ = snd_buf_.empty() ? snd_nxt_ : snd_buf_.front().sn; }

    void parse_ack(std::uint32_t sn) {
        if (diff(sn, snd_una_) < 0 || diff(sn, snd_nxt_) >= 0) return;
        for (std::size_t i = 0; i < snd_buf_.size(); i++) {
            if (sn == snd_buf_[i].sn) {
                snd_buf_.erase(snd_buf_.begin() + static_cast<std::ptrdiff_t>(i));
                return;
            }
            if (diff(sn, snd_buf_[i].sn) < 0) return;
        }
    }

    void parse_fastack(std::uint32_t sn, std::uint32_t ts) {
        if (diff(sn, snd_una_) < 0 || diff(sn, snd_nxt_) >= 0) return;
        for (auto& seg : snd_buf_) {
            if (diff(sn, seg.sn) < 0) break;
            if (sn != seg.sn) {
                if (diff(ts, seg.ts) >= 0) seg.fastack++;  // IKCP_FASTACK_CONSERVE
            }
        }
    }

    void update_ack(std::int32_t rtt) {
        if (rx_srtt_ == 0) {
            rx_srtt_ = rtt;
            rx_rttval_ = rtt / 2;
        } else {
            long delta = rtt - rx_srtt_;
            if (delta < 0) delta = -delta;
            rx_rttval_ = (3 * rx_rttval_ + static_cast<std::int32_t>(delta)) / 4;
            rx_srtt_ = (7 * rx_srtt_ + rtt) / 8;
            if (rx_srtt_ < 1) rx_srtt_ = 1;
        }
        const std::int32_t rto = rx_srtt_ + static_cast<std::int32_t>(std::max<std::uint32_t>(interval_, 4 * rx_rttval_));
        rx_rto_ = std::min<std::int32_t>(std::max<std::int32_t>(rx_minrto_, rto), static_cast<std::int32_t>(kRtoMax));
    }

    void parse_data(Segment& newseg) {
        const std::uint32_t sn = newseg.sn;
        if (diff(sn, rcv_nxt_ + rcv_wnd_) >= 0 || diff(sn, rcv_nxt_) < 0) return;

        std::size_t pos = 0;
        bool dup = false;
        for (std::size_t i = rcv_buf_.size(); i-- > 0;) {
            if (rcv_buf_[i].sn == sn) { dup = true; break; }
            if (diff(sn, rcv_buf_[i].sn) > 0) { pos = i + 1; break; }
        }
        if (!dup) rcv_buf_.insert(rcv_buf_.begin() + static_cast<std::ptrdiff_t>(pos), std::move(newseg));

        move_rcv_to_queue();
    }

    // move contiguous in-order segments from rcv_buf -> rcv_queue
    void move_rcv_to_queue() {
        while (!rcv_buf_.empty() && rcv_buf_.front().sn == rcv_nxt_ && rcv_queue_.size() < rcv_wnd_) {
            rcv_queue_.push_back(std::move(rcv_buf_.front()));
            rcv_buf_.pop_front();
            rcv_nxt_++;
        }
    }

    int peek_size() const {
        if (rcv_queue_.empty()) return -1;
        const auto& front = rcv_queue_.front();
        if (front.frg == 0) return static_cast<int>(front.len);
        if (rcv_queue_.size() < static_cast<std::size_t>(front.frg) + 1) return -1;
        int length = 0;
        for (auto it = rcv_queue_.begin(); it != rcv_queue_.end(); ++it) {
            length += static_cast<int>(it->len);
            if (it->frg == 0) break;
        }
        return length;
    }

    // deliver every complete, in-order message currently staged in rcv_queue
    void drain() {
        while (true) {
            const int sz = peek_size();
            if (sz < 0) break;
            const bool recover = (rcv_queue_.size() >= rcv_wnd_);
            std::vector<std::uint8_t> msg;
            msg.reserve(static_cast<std::size_t>(sz));
            while (static_cast<int>(msg.size()) < sz) {
                Segment s = std::move(rcv_queue_.front());
                rcv_queue_.pop_front();
                msg.insert(msg.end(), s.data.begin(), s.data.end());
                if (s.frg == 0) break;
            }
            move_rcv_to_queue();
            if (rcv_queue_.size() < rcv_wnd_ && recover) probe_ |= kAskTell;
            if (deliver_) deliver_(msg);
        }
    }

    //------------------------------------------------------------------ session
    std::uint32_t conv_ = 0;
    std::uint32_t state_ = 0;
    std::uint32_t mtu_ = kMtuDef;
    std::uint32_t mss_ = mtu_ - kOverhead;

    std::uint32_t snd_una_ = 0;
    std::uint32_t snd_nxt_ = 0;
    std::uint32_t rcv_nxt_ = 0;
    std::uint32_t ts_recent_ = 0;
    std::uint32_t ts_lastack_ = 0;

    std::uint32_t ssthresh_ = kThresholdInit;
    std::int32_t rx_rttval_ = 0;
    std::int32_t rx_srtt_ = 0;
    std::int32_t rx_rto_ = static_cast<std::int32_t>(kRtoDef);
    std::int32_t rx_minrto_ = static_cast<std::int32_t>(kRtoMin);

    std::uint32_t snd_wnd_ = kWndSnd;
    std::uint32_t rcv_wnd_ = kWndRcv;
    std::uint32_t rmt_wnd_ = kWndRcv;
    std::uint32_t cwnd_ = 0;
    std::uint32_t incr_ = 0;
    std::uint32_t probe_ = 0;

    std::uint32_t interval_ = kInterval;
    std::uint32_t ts_flush_ = kInterval;
    std::uint32_t xmit_ = 0;
    std::uint32_t current_ = 0;
    std::uint32_t updated_ = 0;
    std::uint32_t ts_probe_ = 0;
    std::uint32_t probe_wait_ = 0;
    std::uint32_t dead_link_ = kDeadlink;
    std::uint32_t fastresend_ = 0;
    std::uint32_t fastlimit_ = kFastAckLimit;
    std::uint32_t nocwnd_ = 0;
    std::uint32_t nodelay_ = 0;
    std::uint64_t ackedlen_ = 0;

    std::deque<Segment> snd_queue_;
    std::deque<Segment> rcv_queue_;
    std::deque<Segment> snd_buf_;
    std::deque<Segment> rcv_buf_;
    std::vector<std::pair<std::uint32_t, std::uint32_t>> acklist_;
    std::vector<std::uint8_t> out_;

    DatagramSender sender_;
    PayloadHandler deliver_;
};

}  // namespace xsnet