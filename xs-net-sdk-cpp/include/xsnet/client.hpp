#pragma once

#include <asio.hpp>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace xsnet {

struct NetworkConfig {
    std::string host = "127.0.0.1";
    std::uint16_t tcp_port = 8082;
    std::uint16_t udp_port = 2002;
    std::size_t udp_window_size = 32;
    std::chrono::milliseconds retry_interval{200};
    std::chrono::milliseconds heartbeat_interval{10000};
};

using MessageHandler = std::function<void(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload)>;
using ErrorHandler = std::function<void(const std::string&)>;

class Connection {
public:
    virtual ~Connection() = default;
    virtual void connect(const std::string& jwt) = 0;
    virtual void send(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) = 0;
    virtual void close() = 0;
    [[nodiscard]] virtual bool connected() const = 0;
    void set_message_handler(MessageHandler handler) { message_handler_ = std::move(handler); }
    void set_error_handler(ErrorHandler handler) { error_handler_ = std::move(handler); }

protected:
    void deliver(const std::vector<std::uint8_t>& body);
    void report_error(const std::string& error) const;

    MessageHandler message_handler_;
    ErrorHandler error_handler_;
};

class TcpConnection final : public Connection {
public:
    explicit TcpConnection(NetworkConfig config);
    ~TcpConnection() override;
    void connect(const std::string& jwt) override;
    void send(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) override;
    void close() override;
    [[nodiscard]] bool connected() const override;

private:
    void read_loop();
    void write_frame(const std::vector<std::uint8_t>& body);

    NetworkConfig config_;
    asio::io_context context_;
    asio::ip::tcp::socket socket_{context_};
    std::thread reader_;
    std::atomic_bool running_{false};
    mutable std::mutex write_mutex_;
};

class UdpConnection final : public Connection {
public:
    explicit UdpConnection(NetworkConfig config);
    ~UdpConnection() override;
    void connect(const std::string& jwt) override;
    void send(std::int32_t rpc_number, const std::vector<std::uint8_t>& payload) override;
    void close() override;
    [[nodiscard]] bool connected() const override;

private:
    void receive_loop();
    void maintenance_loop();
    void send_datagram(const std::vector<std::uint8_t>& datagram);

    NetworkConfig config_;
    asio::io_context context_;
    asio::ip::udp::socket socket_{context_};
    std::unique_ptr<class ReliableUdp> reliable_;
    std::thread receiver_;
    std::thread maintenance_;
    std::atomic_bool running_{false};
    std::atomic_bool handshaken_{false};
    mutable std::mutex socket_mutex_;
};

}  // namespace xsnet
