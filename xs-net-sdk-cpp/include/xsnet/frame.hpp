#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace xsnet {

constexpr std::size_t kMaxFrameLength = 65536;
constexpr std::size_t kUdpHeaderLength = 15;

enum class UdpFrameType : std::uint8_t {
    data = 1,
    ack = 2,
    hello = 3,
    heartbeat = 4,
    error = 5
};

struct UdpPacket {
    UdpFrameType type;
    std::uint32_t sequence;
    std::uint32_t acknowledgement;
    std::vector<std::uint8_t> payload;
};

inline void write_u32_be(std::uint8_t* target, std::uint32_t value) {
    target[0] = static_cast<std::uint8_t>(value >> 24U);
    target[1] = static_cast<std::uint8_t>(value >> 16U);
    target[2] = static_cast<std::uint8_t>(value >> 8U);
    target[3] = static_cast<std::uint8_t>(value);
}

inline std::uint32_t read_u32_be(const std::uint8_t* source) {
    return (static_cast<std::uint32_t>(source[0]) << 24U)
        | (static_cast<std::uint32_t>(source[1]) << 16U)
        | (static_cast<std::uint32_t>(source[2]) << 8U)
        | static_cast<std::uint32_t>(source[3]);
}

inline std::vector<std::uint8_t> encode_tcp_frame(const std::vector<std::uint8_t>& body) {
    if (body.empty() || body.size() > kMaxFrameLength) {
        throw std::invalid_argument("invalid TCP frame body length");
    }
    std::vector<std::uint8_t> result(4 + body.size());
    write_u32_be(result.data(), static_cast<std::uint32_t>(body.size()));
    std::copy(body.begin(), body.end(), result.begin() + 4);
    return result;
}

inline std::vector<std::uint8_t> encode_udp_packet(UdpFrameType type, std::uint32_t sequence,
                                                    std::uint32_t acknowledgement,
                                                    const std::vector<std::uint8_t>& payload = {}) {
    if (payload.size() > kMaxFrameLength - kUdpHeaderLength) {
        throw std::invalid_argument("UDP payload is too large");
    }
    std::vector<std::uint8_t> result(kUdpHeaderLength + payload.size());
    result[0] = 0x58;
    result[1] = 0x53;
    result[2] = static_cast<std::uint8_t>(type);
    write_u32_be(result.data() + 3, sequence);
    write_u32_be(result.data() + 7, acknowledgement);
    write_u32_be(result.data() + 11, static_cast<std::uint32_t>(payload.size()));
    std::copy(payload.begin(), payload.end(), result.begin() + kUdpHeaderLength);
    return result;
}

inline bool decode_udp_packet(const std::uint8_t* data, std::size_t size, UdpPacket& result) {
    if (size < kUdpHeaderLength || data[0] != 0x58 || data[1] != 0x53) {
        return false;
    }
    const auto payload_length = read_u32_be(data + 11);
    if (payload_length > kMaxFrameLength - kUdpHeaderLength || size < kUdpHeaderLength + payload_length) {
        return false;
    }
    result = {
        static_cast<UdpFrameType>(data[2]),
        read_u32_be(data + 3),
        read_u32_be(data + 7),
        std::vector<std::uint8_t>(data + kUdpHeaderLength, data + kUdpHeaderLength + payload_length)
    };
    return true;
}

}  // namespace xsnet
