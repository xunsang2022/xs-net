#include "xsnet/frame.hpp"
#include "xsnet/reliable_udp.hpp"

#include <cassert>
#include <vector>

int main() {
    const std::vector<std::uint8_t> payload{1, 2, 3};
    const auto tcp_frame = xsnet::encode_tcp_frame(payload);
    assert(xsnet::read_u32_be(tcp_frame.data()) == payload.size());

    xsnet::UdpPacket packet{};
    const auto udp_frame = xsnet::encode_udp_packet(xsnet::UdpFrameType::data, 7, 3, payload);
    assert(xsnet::decode_udp_packet(udp_frame.data(), udp_frame.size(), packet));
    assert(packet.sequence == 7);
    assert(packet.random == 3);
    assert(packet.payload == payload);

    std::vector<int> delivered;
    xsnet::ReliableUdp reliable([](const auto&) {}, [&delivered](const auto& body) { delivered.push_back(body.at(0)); });
    reliable.receive({xsnet::UdpFrameType::data, 1, 0, {1}});
    reliable.receive({xsnet::UdpFrameType::data, 3, 0, {3}});
    reliable.receive({xsnet::UdpFrameType::data, 2, 0, {2}});
    assert((delivered == std::vector<int>{1, 2, 3}));
}
