#include "xsnet/client.hpp"

#include "xsnet/frame.hpp"
#include "xsnet/reliable_udp.hpp"

#include "net_message.pb.h"

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
    write_frame(std::vector<std::uint8_t>(jwt.begin(), jwt.end()));
    running_ = true;
    reader_ = std::thread(&TcpConnection::read_loop, this);
}

void TcpConnection::send(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) {
    write_frame(encode_message(rpc_number, payload));
}

void TcpConnection::close() {
    const bool was_running = running_.exchange(false);
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
    return running_ && socket_.is_open();
}

void TcpConnection::read_loop() {
    try {
        while (running_) {
            std::array<std::uint8_t, 4> header{};
            asio::read(socket_, asio::buffer(header));
            const auto length = read_u32_be(header.data());
            if (length == 0 || length > kMaxFrameLength) {
                throw std::runtime_error("received invalid TCP frame length");
            }
            std::vector<std::uint8_t> body(length);
            asio::read(socket_, asio::buffer(body));
            deliver(body);
        }
    } catch (const std::exception& exception) {
        if (running_) {
            report_error(std::string("TCP read failure: ") + exception.what());
        }
    }
    running_ = false;
}

void TcpConnection::write_frame(const std::vector<std::uint8_t>& body) {
    const auto frame = encode_tcp_frame(body);
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
    reliable_ = std::make_unique<ReliableUdp>(
        [this](const auto& datagram) { send_datagram(datagram); },
        [this](const auto& body) { deliver(body); },
        config_.udp_window_size);
    receiver_ = std::thread(&UdpConnection::receive_loop, this);
    maintenance_ = std::thread(&UdpConnection::maintenance_loop, this);
    send_datagram(encode_udp_packet(UdpFrameType::hello, 0, 0, std::vector<std::uint8_t>(jwt.begin(), jwt.end())));
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
            if (!handshaken_ && packet.type == UdpFrameType::ack) {
                handshaken_ = true;
                continue;
            }
            if (handshaken_ && reliable_) {
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
            send_datagram(encode_udp_packet(UdpFrameType::heartbeat, 0, reliable_ ? reliable_->contiguous_ack() : 0));
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

}  // namespace xsnet
