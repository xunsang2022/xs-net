#include "xsnet/kcp.hpp"

#include <cassert>
#include <cstdint>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// local big-endian helper used to craft raw KCP PUSH datagrams for the
// receiver-side (reordering / fragment reassembly) tests.
// ---------------------------------------------------------------------------
static std::vector<std::uint8_t> make_push(std::uint32_t conv, std::uint32_t sn, std::uint8_t frg,
                                           std::uint32_t ts, const std::vector<std::uint8_t>& data) {
    std::vector<std::uint8_t> out(24 + data.size());
    std::uint8_t* p = out.data();
    auto w32 = [&p](std::uint32_t v) {
        p[0] = static_cast<std::uint8_t>(v >> 24U);
        p[1] = static_cast<std::uint8_t>(v >> 16U);
        p[2] = static_cast<std::uint8_t>(v >> 8U);
        p[3] = static_cast<std::uint8_t>(v);
        p += 4;
    };
    w32(conv);                 // conv
    *p++ = 81;                 // cmd = PUSH
    *p++ = frg;                // frg
    p[0] = static_cast<std::uint8_t>(512 >> 8U);  // wnd (16-bit BE)
    p[1] = static_cast<std::uint8_t>(512);
    p += 2;
    w32(ts);
    w32(sn);
    w32(0);                    // una
    w32(static_cast<std::uint32_t>(data.size()));  // len
    std::memcpy(p, data.data(), data.size());
    (void)ts;
    return out;
}

// ---------------------------------------------------------------------------
// small helper: feed a crafted PUSH datagram to a Kcp receive session.
// ---------------------------------------------------------------------------
template <typename K, typename D>
static void feed(K& kcp, const D& datagram) {
    kcp.input(datagram.data(), datagram.size());
}

int main() {
    // ---- 1. unsigned 32-bit wrap-around comparator -------------------------
    assert(xsnet::Kcp::diff(5U, 1U) > 0);
    assert(xsnet::Kcp::diff(1U, 5U) < 0);
    assert(xsnet::Kcp::diff(0U, 0xFFFFFFFFU) > 0);   // 0 is "one ahead" of max
    assert(xsnet::Kcp::diff(0xFFFFFFFFU, 0U) < 0);   // max wraps below 0
    assert(xsnet::Kcp::diff(0x80000000U, 0U) < 0);   // large positive delta reads as negative

    // ---- 2. end-to-end send/recv via a loopback datagram link --------------
    {
        std::vector<std::vector<std::uint8_t>> delivered;
        const std::uint32_t conv = 7;
        xsnet::Kcp sender(conv, [](const std::vector<std::uint8_t>&) {}, [](const std::vector<std::uint8_t>&) {});
        xsnet::Kcp receiver(
            conv,
            [](const std::vector<std::uint8_t>&) {},
            [&delivered](const std::vector<std::uint8_t>& body) { delivered.push_back(body); });
        sender.set_nodelay(1, 10, 2, 1);
        receiver.set_nodelay(1, 10, 2, 1);

        xsnet::Kcp::DatagramSender link = [&](const std::vector<std::uint8_t>& dg) {
            receiver.input(dg.data(), dg.size());
        };
        // re-wire sender output to the receiver (only for this single packet test)
        xsnet::Kcp sender2(
            conv, std::move(link), [](const std::vector<std::uint8_t>&) {});
        sender2.set_nodelay(1, 10, 2, 1);
        sender2.send(std::vector<std::uint8_t>{1, 2, 3});
        sender2.update(1000);
        assert(delivered.size() == 1);
        assert(delivered[0] == (std::vector<std::uint8_t>{1, 2, 3}));
    }

    // ---- 3. receiver-side out-of-order reordering ---------------------------
    {
        std::vector<std::vector<std::uint8_t>> delivered;
        xsnet::Kcp recv(1, [](const std::vector<std::uint8_t>&) {},
                        [&delivered](const std::vector<std::uint8_t>& body) { delivered.push_back(body); });
        feed(recv, make_push(1, 1, 0, 10, std::vector<std::uint8_t>{7}));
        feed(recv, make_push(1, 0, 0, 11, std::vector<std::uint8_t>{6}));  // arrives late but is "next"
        assert(delivered.size() == 2);
        assert(delivered[0] == (std::vector<std::uint8_t>{6}));
        assert(delivered[1] == (std::vector<std::uint8_t>{7}));
    }

    // ---- 4. fragment reassembly under out-of-order arrival ------------------
    {
        std::vector<std::vector<std::uint8_t>> delivered;
        xsnet::Kcp recv(2, [](const std::vector<std::uint8_t>&) {},
                        [&delivered](const std::vector<std::uint8_t>& body) { delivered.push_back(body); });
        // one application message split into 3 fragments: sn 0/1/2, frg 2/1/0
        feed(recv, make_push(2, 2, 0, 20, std::vector<std::uint8_t>{'G', 'H', 'I'}));
        feed(recv, make_push(2, 0, 2, 21, std::vector<std::uint8_t>{'A', 'B', 'C'}));
        feed(recv, make_push(2, 1, 1, 22, std::vector<std::uint8_t>{'D', 'E', 'F'}));
        assert(delivered.size() == 1);
        assert(delivered[0] == (std::vector<std::uint8_t>{'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'}));
    }

    // ---- 5. end-to-end fragmentation via real loopback datagram link -------
    {
        std::vector<std::uint8_t> original;
        for (int i = 0; i < 100; ++i) original.push_back(static_cast<std::uint8_t>(i));
        std::vector<std::vector<std::uint8_t>> delivered;
        xsnet::Kcp receiver(
            3, [](const std::vector<std::uint8_t>&) {},
            [&delivered](const std::vector<std::uint8_t>& body) { delivered.push_back(body); });
        receiver.set_mtu(64);  // mss = 40 -> 100 bytes needs 3 fragments
        xsnet::Kcp sender(
            3,
            [&](const std::vector<std::uint8_t>& dg) { receiver.input(dg.data(), dg.size()); },
            [](const std::vector<std::uint8_t>&) {});
        sender.set_nodelay(1, 10, 2, 1);
        sender.set_mtu(64);
        sender.send(original);
        sender.update(1000);
        assert(delivered.size() == 1);
        assert(delivered[0] == original);
    }

    // ---- 6. RTO timeout retransmission -------------------------------------
    {
        int sends = 0;
        std::vector<std::vector<std::uint8_t>> datagrams;
        xsnet::Kcp sender(
            4,
            [&](const std::vector<std::uint8_t>& dg) { ++sends; datagrams.push_back(dg); },
            [](const std::vector<std::uint8_t>&) {});
        sender.set_nodelay(1, 10, 2, 1);
        sender.send(std::vector<std::uint8_t>{0xAB});  // never acked -> must be retransmitted

        sender.update(1000);   // first flush
        assert(sends == 1);
        assert(datagrams[0].size() == 24 + 1);

        // parse seq sn from the PUSH header (offset 12..15, big-endian)
        auto read_sn = [](const std::vector<std::uint8_t>& dg) {
            return (static_cast<std::uint32_t>(dg[12]) << 24U) | (static_cast<std::uint32_t>(dg[13]) << 16U)
                | (static_cast<std::uint32_t>(dg[14]) << 8U) | static_cast<std::uint32_t>(dg[15]);
        };
        const std::uint32_t first_sn = read_sn(datagrams[0]);

        sender.update(1300);   // initial rto = IKCP_RTO_DEF(200) now expired -> resend
        assert(sends >= 2);
        assert(read_sn(datagrams[1]) == first_sn);  // same packet retransmitted
    }

    return 0;
}