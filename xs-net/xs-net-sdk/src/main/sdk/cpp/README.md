# XS Net C++ SDK

The C++ SDK targets C++17 and uses Standalone Asio plus Protobuf. It implements the XS Net TCP and reliable UDP transports: JWT handshake, `NetMessage` envelopes, length framing, cumulative ACK, retransmission and route delivery callbacks.

## Generate Protocol Sources

```powershell
From the repository root, run `.\scripts\Generate-Sdks.ps1 -Only Cpp -Protoc protoc`.
```

The command copies all game and framework `.proto` files into `target/code/cpp/proto/` and writes C++ output to the ignored `target/code/cpp/generated/` directory.

## Build

Install Protobuf 25.5 and Standalone Asio through your package manager, then configure CMake:

```powershell
cmake -S . -B build -DProtobuf_ROOT=<protobuf-prefix>
cmake --build build
ctest --test-dir build --output-on-failure
```

`target/code/cpp/generated/` and `target/code/cpp/build/` are local build artifacts and are not committed.
