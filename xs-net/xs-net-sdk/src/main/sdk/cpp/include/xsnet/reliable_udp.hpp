#pragma once

#include "xsnet/frame.hpp"

#include <algorithm>
#include <functional>
#include <map>
#include <mutex>
#include <utility>
#include <vector>

namespace xsnet {

class ReliableUdp {
public:
    using DatagramSender = std::function<void(const std::vector<std::uint8_t>&)>;
    using PayloadHandler = std::function<void(const std::vector<std::uint8_t>&)>;

    ReliableUdp(DatagramSender sender, PayloadHandler deliver, std::size_t window_size = 32)
        : sender_(std::move(sender)), deliver_(std::move(deliver)), window_size_(std::max<std::size_t>(1, window_size)) {
    }

    void send(const std::vector<std::uint8_t>& payload) {
        std::vector<std::uint8_t> datagram;
        {
            std::lock_guard lock(mutex_);
            if (in_flight_.size() >= window_size_) {
                in_flight_.erase(in_flight_.begin());
            }
            const auto sequence = send_sequence_++;
            in_flight_[sequence] = payload;
            datagram = encode_udp_packet(UdpFrameType::data, sequence, highest_ack_, payload);
        }
        sender_(datagram);
    }

    void receive(const UdpPacket& packet) {
        std::vector<std::vector<std::uint8_t>> deliveries;
        std::vector<std::uint8_t> acknowledgement;
        {
            std::lock_guard lock(mutex_);
            if (packet.acknowledgement > 0) {
                in_flight_.erase(in_flight_.begin(), in_flight_.upper_bound(packet.acknowledgement));
            }
            if (packet.type != UdpFrameType::data) {
                return;
            }
            if (packet.sequence >= next_receive_sequence_) {
                if (packet.sequence == next_receive_sequence_) {
                    queue_delivery(packet.sequence, packet.payload, deliveries);
                    while (auto pending = reordering_.find(next_receive_sequence_); pending != reordering_.end()) {
                        auto payload = std::move(pending->second);
                        reordering_.erase(pending);
                        queue_delivery(next_receive_sequence_, payload, deliveries);
                    }
                } else if (reordering_.size() < window_size_ * 2) {
                    reordering_.emplace(packet.sequence, packet.payload);
                }
            }
            acknowledgement = encode_udp_packet(UdpFrameType::ack, 0, highest_ack_);
        }
        for (const auto& payload : deliveries) {
            deliver_(payload);
        }
        sender_(acknowledgement);
    }

    void retry_outstanding() {
        std::vector<std::vector<std::uint8_t>> datagrams;
        {
            std::lock_guard lock(mutex_);
            for (const auto& [sequence, payload] : in_flight_) {
                datagrams.push_back(encode_udp_packet(UdpFrameType::data, sequence, highest_ack_, payload));
            }
        }
        for (const auto& datagram : datagrams) {
            sender_(datagram);
        }
    }

    [[nodiscard]] std::uint32_t contiguous_ack() const {
        std::lock_guard lock(mutex_);
        return highest_ack_;
    }

    [[nodiscard]] std::size_t outstanding() const {
        std::lock_guard lock(mutex_);
        return in_flight_.size();
    }

private:
    void queue_delivery(std::uint32_t sequence, const std::vector<std::uint8_t>& payload,
                        std::vector<std::vector<std::uint8_t>>& deliveries) {
        next_receive_sequence_ = sequence + 1;
        highest_ack_ = sequence;
        deliveries.push_back(payload);
    }

    DatagramSender sender_;
    PayloadHandler deliver_;
    std::size_t window_size_;
    std::map<std::uint32_t, std::vector<std::uint8_t>> in_flight_;
    std::map<std::uint32_t, std::vector<std::uint8_t>> reordering_;
    std::uint32_t send_sequence_ = 1;
    std::uint32_t next_receive_sequence_ = 1;
    std::uint32_t highest_ack_ = 0;
    mutable std::mutex mutex_;
};

}  // namespace xsnet
