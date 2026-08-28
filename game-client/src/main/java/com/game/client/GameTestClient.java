package com.game.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.message.rpc.GameRpcMessages;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.pb.NetMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class GameTestClient implements AutoCloseable {

    private final GameClientApplication.ClientSettings settings;
    private final ObjectMapper objectMapper;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    public GameTestClient(GameClientApplication.ClientSettings settings) {
        this.settings = Objects.requireNonNull(settings);
        this.objectMapper = new ObjectMapper();
    }

    public synchronized void connect(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        closeSocket();
        Socket newSocket = new Socket();
        try {
            newSocket.connect(
                    new InetSocketAddress(settings.nettyHost(), settings.nettyPort()),
                    (int) settings.timeout().toMillis());
            newSocket.setSoTimeout((int) settings.timeout().toMillis());
            DataInputStream newInput = new DataInputStream(new BufferedInputStream(newSocket.getInputStream()));
            DataOutputStream newOutput = new DataOutputStream(new BufferedOutputStream(newSocket.getOutputStream()));
            NettyFrameCodec.write(newOutput, token.getBytes(StandardCharsets.UTF_8));
            socket = newSocket;
            input = newInput;
            output = newOutput;
        } catch (IOException e) {
            try {
                newSocket.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    public synchronized void send(int rpcNum, Object payload) throws IOException {
        if (output == null || socket == null || socket.isClosed()) {
            throw new IllegalStateException("Connect to the gateway before sending messages");
        }
        RpcMessageSpec<? extends MessageLite> spec = GameRpcMessages.INSTANCE.find(rpcNum);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown rpc number: " + rpcNum);
        }
        if (!spec.publicVisible()) {
            throw new IllegalArgumentException("RPC is not public: " + rpcNum);
        }
        Object dto = objectMapper.convertValue(payload, spec.dtoClass());
        MessageLite protobufMessage = spec.encode(dto);
        NetMessage message = NetMessage.newBuilder()
                .setRpcNum(rpcNum)
                .setData(ByteString.copyFrom(protobufMessage.toByteArray()))
                .build();
        NettyFrameCodec.write(output, message.toByteArray());
    }

    @Override
    public synchronized void close() throws IOException {
        closeSocket();
    }

    private void closeSocket() throws IOException {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } finally {
            socket = null;
            input = null;
            output = null;
        }
    }
}
