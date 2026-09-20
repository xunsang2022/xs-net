#include "xsnet/client.hpp"

#include "xsnet/frame.hpp"
#include "xsnet/kcp.hpp"
#include "xsnet/reliable_udp.hpp"

#include "net_message.pb.h"

#include <array>
#include <chrono>
#include <cstring>
#include <stdexcept>

namespace xsnet {

namespace {

std::vector<std::uint8_t> encode_message(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) {
    xs::net::message::NetMessage message;
    message.set_rpc_num(rpc_number);
    message.set_data(payload.data(), static_cast<int>(payload.size()));
    std::vector<std::uint8_t> body(static_cast<std::size_t>(message.ByteSizeLong()));
    if (!message.SerializeToArray(body.data(), static_cast<int>(body.size()))) {
        throw std::runtime_error("failed to encode NetMessage");
    }
    return body;
}

// Monotonic milliseconds since a fixed (arbitrary) epoch, truncated to 32 bits.
// KCP only compares timestamps with int32 deltas, so a per-side relative clock is
// sufficient; both endpoints use their own steady clocks.
std::uint32_t now_ms() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
    return static_cast<std::uint32_t>(ms);
}

// FNV-1a 32-bit hash over the JWT, used as the KCP conversation id shared with
// the server. Stable across runs and encoding independent.
std::uint32_t session_conv(const std::string& jwt) {
    std::uint32_t hash = 2166136261U;
    for (const auto c : jwt) {
        hash ^= static_cast<std::uint8_t>(c);
        hash *= 16777619U;
    }
    return hash ? hash : 1U;
}

}  // namespace

void Connection::deliver(const std::vector<std::uint8_t>& body) {
    xs::net::message::NetMessage message;
    if (!message.ParseFromArray(body.data(), static_cast<int>(body.size()))) {
        report_error("received invalid NetMessage protobuf");
        return;
    }
    if (message_handler_) {
        const auto& data = message.data();
        message_handler_(message.rpc_num(), std::vector<std::uint8_t>(data.begin(), data.end()));
    }
}

void Connection::report_error(const std::string& error) const {
    if (error_handler_) {
        error_handler_(error);
    }
}

TcpConnection::TcpConnection(NetworkConfig config) : config_(std::move(config)) {
}

TcpConnection::~TcpConnection() {
    close();
}

void TcpConnection::connect(const std::string& jwt) {
    close();
    context_.restart();
    asio::ip::tcp::resolver resolver(context_);
    auto endpoints = resolver.resolve(config_.host, std::to_string(config_.tcp_port));
    asio::connect(socket_, endpoints);
    if (connection_random_ == 0) connection_random_ = now_ms();
    handshaken_ = false;
    write_packet(encode_packet(UdpFrameType::hello, 0, connection_random_,
                               std::vector<std::uint8_t>(jwt.begin(), jwt.end())));
    running_ = true;
    reader_ = std::thread(&TcpConnection::read_loop, this);
    std::unique_lock lock(state_mutex_);
    if (!handshake_cv_.wait_for(lock, std::chrono::seconds(10), [this] {
            return handshaken_.load() || !running_.load();
        }) || !handshaken_) {
        lock.unlock();
        close();
        throw std::runtime_error("TCP handshake timeout");
    }
}

void TcpConnection::send(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) {
    if (!connected()) throw std::logic_error("TCP connection is not authenticated");
    write_packet(encode_packet(UdpFrameType::data, send_sequence_++, connection_random_,
                               encode_message(rpc_number, payload)));
}

void TcpConnection::close() {
    const bool was_running = running_.exchange(false);
    handshaken_ = false;
    handshake_cv_.notify_all();
    asio::error_code ignored;
    socket_.shutdown(asio::ip::tcp::socket::shutdown_both, ignored);
    socket_.close(ignored);
    if (reader_.joinable() && reader_.get_id() != std::this_thread::get_id()) {
        reader_.join();
    }
    if (was_running) {
        report_error("TCP connection closed");
    }
}

bool TcpConnection::connected() const {
    return running_ && handshaken_ && socket_.is_open();
}

void TcpConnection::read_loop() {
    try {
        while (running_) {
            std::array<std::uint8_t, kPacketHeaderLength> header{};
            asio::read(socket_, asio::buffer(header));
            const auto length = read_u32_be(header.data());
            if (length > kMaxFrameLength - kPacketHeaderLength) {
                throw std::runtime_error("received invalid TCP frame length");
            }
            std::vector<std::uint8_t> body(length);
            asio::read(socket_, asio::buffer(body));
            std::vector<std::uint8_t> packet(kPacketHeaderLength + body.size());
            std::copy(header.begin(), header.end(), packet.begin());
            std::copy(body.begin(), body.end(), packet.begin() + kPacketHeaderLength);
            UdpPacket decoded{};
            if (!decode_packet(packet.data(), packet.size(), decoded) || decoded.random != connection_random_) {
                throw std::runtime_error("received invalid XS packet");
            }
            if (decoded.type == UdpFrameType::ack && !handshaken_) {
                handshaken_ = true;
                handshake_cv_.notify_all();
            } else if (decoded.type == UdpFrameType::data) {
                write_packet(encode_packet(UdpFrameType::ack, decoded.sequence, connection_random_));
                deliver(decoded.payload);
            }
        }
    } catch (const std::exception& exception) {
        if (running_) {
            report_error(std::string("TCP read failure: ") + exception.what());
        }
    }
    running_ = false;
    handshaken_ = false;
    handshake_cv_.notify_all();
}

void TcpConnection::write_packet(const std::vector<std::uint8_t>& frame) {
    std::lock_guard lock(write_mutex_);
    asio::write(socket_, asio::buffer(frame));
}

UdpConnection::UdpConnection(NetworkConfig config) : config_(std::move(config)) {
}

UdpConnection::~UdpConnection() {
    close();
}

void UdpConnection::connect(const std::string& jwt) {
    close();
    context_.restart();
    asio::ip::udp::resolver resolver(context_);
    const auto endpoint = *resolver.resolve(asio::ip::udp::v4(), config_.host, std::to_string(config_.udp_port)).begin();
    socket_.open(endpoint.endpoint().protocol());
    socket_.connect(endpoint.endpoint());
    running_ = true;
    handshaken_ = false;
    if (connection_random_ == 0) connection_random_ = now_ms();
    reliable_ = std::make_unique<ReliableUdp>(
        [this](const auto& datagram) { send_datagram(datagram); },
        [this](const auto& body) { deliver(body); },
        config_.udp_window_size, connection_random_);
    receiver_ = std::thread(&UdpConnection::receive_loop, this);
    maintenance_ = std::thread(&UdpConnection::maintenance_loop, this);
    send_datagram(encode_udp_packet(UdpFrameType::hello, 0, connection_random_,
                                    std::vector<std::uint8_t>(jwt.begin(), jwt.end())));
}

void UdpConnection::send(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) {
    if (!connected() || !reliable_) {
        throw std::logic_error("UDP connection is not authenticated");
    }
    reliable_->send(encode_message(rpc_number, payload));
}

void UdpConnection::close() {
    running_ = false;
    handshaken_ = false;
    asio::error_code ignored;
    socket_.close(ignored);
    if (receiver_.joinable() && receiver_.get_id() != std::this_thread::get_id()) {
        receiver_.join();
    }
    if (maintenance_.joinable() && maintenance_.get_id() != std::this_thread::get_id()) {
        maintenance_.join();
    }
    reliable_.reset();
}

bool UdpConnection::connected() const {
    return running_ && handshaken_ && socket_.is_open();
}

void UdpConnection::receive_loop() {
    std::array<std::uint8_t, kMaxFrameLength> buffer{};
    while (running_) {
        try {
            const auto size = socket_.receive(asio::buffer(buffer));
            UdpPacket packet{};
            if (!decode_udp_packet(buffer.data(), size, packet)) {
                continue;
            }
            if (!handshaken_ && packet.type == UdpFrameType::ack && packet.random == connection_random_) {
                handshaken_ = true;
                continue;
            }
            if (handshaken_ && reliable_ && packet.random == connection_random_) {
                reliable_->receive(packet);
            }
        } catch (const std::exception& exception) {
            if (running_) {
                report_error(std::string("UDP receive failure: ") + exception.what());
            }
            break;
        }
    }
    running_ = false;
}

void UdpConnection::maintenance_loop() {
    auto next_retry = std::chrono::steady_clock::now() + config_.retry_interval;
    auto next_heartbeat = std::chrono::steady_clock::now() + config_.heartbeat_interval;
    while (running_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        const auto now = std::chrono::steady_clock::now();
        if (now >= next_retry) {
            if (reliable_) reliable_->retry_outstanding();
            next_retry = now + config_.retry_interval;
        }
        if (handshaken_ && now >= next_heartbeat) {
            send_datagram(encode_udp_packet(UdpFrameType::heartbeat,
                                            reliable_ ? reliable_->contiguous_ack() : 0,
                                            connection_random_));
            next_heartbeat = now + config_.heartbeat_interval;
        }
    }
}

void UdpConnection::send_datagram(const std::vector<std::uint8_t>& datagram) {
    std::lock_guard lock(socket_mutex_);
    if (socket_.is_open()) {
        socket_.send(asio::buffer(datagram));
    }
}

void KcpConnection::connect(const std::string& jwt) {
    close();
    context_.restart();
    asio::ip::udp::resolver resolver(context_);
    const auto endpoint =
        *resolver.resolve(asio::ip::udp::v4(), config_.host, std::to_string(config_.kcp_port)).begin();
    socket_.open(endpoint.endpoint().protocol());
    socket_.connect(endpoint.endpoint());
    running_ = true;
    handshaken_ = false;
    last_activity_.store(now_ms(), std::memory_order_relaxed);

    kcp_ = std::make_unique<Kcp>(
        session_conv(jwt),
        [this](const std::vector<std::uint8_t>& datagram) { send_datagram(datagram); },
        [this](const std::vector<std::uint8_t>& body) { on_kcp_payload(body); });
    kcp_->set_nodelay(1, 10, 2, 1);  // fast mode + disable implicit congestion-window cold start
    kcp_->set_interval(10);
    kcp_->set_wndsize(static_cast<int>(config_.udp_window_size), 128);

    receiver_ = std::thread(&KcpConnection::receive_loop, this);
    maintenance_ = std::thread(&KcpConnection::maintenance_loop, this);

    // first application message = jwt; queue it and flush immediately.
    {
        std::lock_guard lock(kcp_mutex_);
        kcp_->send(std::vector<std::uint8_t>(jwt.begin(), jwt.end()));
        kcp_->update(now_ms());
    }
}

void KcpConnection::send(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) {
    if (!connected() || !kcp_) {
        throw std::logic_error("KCP connection is not authenticated");
    }
    std::lock_guard lock(kcp_mutex_);
    kcp_->send(encode_message(rpc_number, payload));
    kcp_->update(now_ms());
    last_activity_.store(now_ms(), std::memory_order_relaxed);
}

void KcpConnection::close() {
    running_ = false;
    handshaken_ = false;
    asio::error_code ignored;
    socket_.close(ignored);
    if (receiver_.joinable() && receiver_.get_id() != std::this_thread::get_id()) {
        receiver_.join();
    }
    if (maintenance_.joinable() && maintenance_.get_id() != std::this_thread::get_id()) {
        maintenance_.join();
    }
    std::lock_guard lock(kcp_mutex_);
    kcp_.reset();
}

bool KcpConnection::connected() const {
    return running_ && handshaken_ && socket_.is_open();
}

void KcpConnection::receive_loop() {
    std::array<std::uint8_t, kMaxFrameLength> buffer{};
    while (running_) {
        try {
            const auto size = socket_.receive(asio::buffer(buffer));
            {
                std::lock_guard lock(kcp_mutex_);
                kcp_->input(buffer.data(), static_cast<std::size_t>(size));
            }
        } catch (const std::exception& exception) {
            if (running_) {
                report_error(std::string("KCP receive failure: ") + exception.what());
            }
            break;
        }
    }
    running_ = false;
}

void KcpConnection::maintenance_loop() {
    while (running_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        std::lock_guard lock(kcp_mutex_);
        kcp_->update(now_ms());
        // idle keepalive / heartbeat: push a zero-length message so RTO and
        // dead-link detection keep running and the server can observe the beat.
        if (handshaken_ &&
            now_ms() - last_activity_.load(std::memory_order_relaxed) >=
                static_cast<std::uint64_t>(config_.heartbeat_interval.count())) {
            kcp_->send({});
            last_activity_.store(now_ms(), std::memory_order_relaxed);
        }
    }
}

void KcpConnection::send_datagram(const std::vector<std::uint8_t>& datagram) {
    std::lock_guard lock(socket_mutex_);
    if (socket_.is_open()) {
        socket_.send(asio::buffer(datagram));
    }
}

void KcpConnection::on_kcp_payload(const std::vector<std::uint8_t>& body) {
    if (!handshaken_) {
        if (body.empty()) {
            handshaken_ = true;  // server acknowledged auth with an empty message
            return;
        }
        report_error("KCP received data before auth handshake");
        return;
    }
    if (body.empty()) {
        return;  // keepalive heartbeat, nothing to route
    }
    deliver(body);
}

std::vector<std::uint8_t> KcpConnection::encode_message(std::int32_t rpc_number,
                                                         const std::vector<std::uint8_t>& payload) const {
    return ::xsnet::encode_message(rpc_number, payload);
}

}  // namespace xsnet
