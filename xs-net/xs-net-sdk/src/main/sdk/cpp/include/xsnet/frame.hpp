#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace xsnet {

constexpr std::size_t kMaxFrameLength = 65536;
constexpr std::size_t kPacketHeaderLength = 18;

enum class UdpFrameType : std::uint16_t {
    data = 1,
    ack = 2,
    hello = 3,
    heartbeat = 4,
    error = 5
};

struct UdpPacket {
    UdpFrameType type;
    std::uint32_t sequence;
    std::uint32_t random;
    std::vector<std::uint8_t> payload;
};

inline void write_u16_be(std::uint8_t* target, std::uint16_t value) {
    target[0] = static_cast<std::uint8_t>(value >> 8U);
    target[1] = static_cast<std::uint8_t>(value);
}

inline std::uint16_t read_u16_be(const std::uint8_t* source) {
    return static_cast<std::uint16_t>((static_cast<std::uint16_t>(source[0]) << 8U) | source[1]);
}

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

inline std::uint16_t crc16(const std::uint8_t* data, std::size_t offset, std::size_t length) {
    std::uint32_t crc = 0xffffU;
    for (std::size_t index = offset; index < offset + length; ++index) {
        crc ^= static_cast<std::uint32_t>(data[index]) << 8U;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000U) != 0 ? (crc << 1U) ^ 0x1021U : crc << 1U;
            crc &= 0xffffU;
        }
    }
    return static_cast<std::uint16_t>(crc);
}

inline std::vector<std::uint8_t> encode_packet(UdpFrameType type, std::uint32_t sequence,
                                                std::uint32_t random,
                                                const std::vector<std::uint8_t>& body = {}) {
    if (body.size() > kMaxFrameLength - kPacketHeaderLength) {
        throw std::invalid_argument("packet body is too large");
    }
    std::vector<std::uint8_t> result(kPacketHeaderLength + body.size());
    write_u32_be(result.data(), static_cast<std::uint32_t>(body.size()));
    write_u16_be(result.data() + 4, static_cast<std::uint16_t>(type));
    write_u32_be(result.data() + 6, sequence);
    write_u32_be(result.data() + 10, random);
    write_u16_be(result.data() + 14, crc16(result.data(), 0, 14));
    write_u16_be(result.data() + 16, crc16(body.data(), 0, body.size()));
    std::copy(body.begin(), body.end(), result.begin() + kPacketHeaderLength);
    return result;
}

inline std::vector<std::uint8_t> encode_tcp_frame(const std::vector<std::uint8_t>& body) {
    return encode_packet(UdpFrameType::data, 0, 0, body);
}

inline std::vector<std::uint8_t> encode_udp_packet(UdpFrameType type, std::uint32_t sequence,
                                                    std::uint32_t random,
                                                    const std::vector<std::uint8_t>& payload = {}) {
    return encode_packet(type, sequence, random, payload);
}

inline bool decode_packet(const std::uint8_t* data, std::size_t size, UdpPacket& result) {
    if (size < kPacketHeaderLength) return false;
    const auto body_length = read_u32_be(data);
    if (body_length > kMaxFrameLength - kPacketHeaderLength || size != kPacketHeaderLength + body_length) {
        return false;
    }
    if (read_u16_be(data + 14) != crc16(data, 0, 14)) return false;
    if (read_u16_be(data + 16) != crc16(data, kPacketHeaderLength, body_length)) return false;
    result = {
        static_cast<UdpFrameType>(read_u16_be(data + 4)),
        read_u32_be(data + 6),
        read_u32_be(data + 10),
        std::vector<std::uint8_t>(data + kPacketHeaderLength, data + kPacketHeaderLength + body_length)
    };
    return true;
}

inline bool decode_udp_packet(const std::uint8_t* data, std::size_t size, UdpPacket& result) {
    return decode_packet(data, size, result);
}

}  // namespace xsnet
