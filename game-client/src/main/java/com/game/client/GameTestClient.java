package com.game.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.protocol.pb.SC_Logout;
import com.xs.net.message.pb.NetMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class GameTestClient implements AutoCloseable {

    private static final int LOGOUT_RPC_NUM = 1003;

    private final GameClientApplication.ClientSettings settings;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    public GameTestClient(GameClientApplication.ClientSettings settings) {
        this.settings = Objects.requireNonNull(settings);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.timeout())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public LoginSession createPlayer() throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(new CreatePlayerRequest(settings.playerId(), settings.playerName()));
        HttpResponse<String> response = sendPost("/api/players/create", requestBody, null);
        requireSuccess(response, "create player");

        JsonNode body = objectMapper.readTree(response.body());
        String token = requiredText(body, "token");
        String playerIdText = requiredText(body, "playerId");
        String playerName = body.path("playerName").asText(settings.playerName());
        long playerId;
        try {
            playerId = Long.parseLong(playerIdText);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid playerId in create-player response: " + playerIdText, e);
        }
        return new LoginSession(playerId, playerName, token);
    }

    public void connect(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        closeSocket();
        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(settings.nettyHost(), settings.nettyPort()), (int) settings.timeout().toMillis());
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

    public SC_Logout logout(LoginSession session) throws IOException, InterruptedException {
        if (input == null || output == null || socket == null || socket.isClosed()) {
            throw new IllegalStateException("Connect to the gateway before logging out");
        }
        String requestBody = objectMapper.writeValueAsString(new LogoutPlayerRequest(String.valueOf(session.playerId())));
        HttpResponse<String> response = sendPost("/api/players/logout", requestBody, session.token());
        requireSuccess(response, "logout player");

        NetMessage netMessage = NetMessage.parseFrom(NettyFrameCodec.read(input));
        if (netMessage.getRpcNum() != LOGOUT_RPC_NUM) {
            throw new IOException("Unexpected rpc number: " + netMessage.getRpcNum());
        }
        SC_Logout logout = SC_Logout.parseFrom(netMessage.getData());
        if (!String.valueOf(session.playerId()).equals(logout.getPlayerId())) {
            throw new IOException("Unexpected logout playerId: " + logout.getPlayerId());
        }
        if (logout.getSessionId().isBlank()) {
            throw new IOException("Logout response has no sessionId");
        }
        return logout;
    }

    @Override
    public void close() throws IOException {
        closeSocket();
    }

    private HttpResponse<String> sendPost(String path, String requestBody, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(settings.gatewayBaseUrl() + path))
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (token != null) {
            request.header("Authorization", token);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void requireSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(operation + " failed: status=" + response.statusCode() + ", body=" + response.body());
        }
    }

    private static String requiredText(JsonNode body, String fieldName) throws IOException {
        String value = body.path(fieldName).asText();
        if (value.isBlank()) {
            throw new IOException("Missing " + fieldName + " in create-player response");
        }
        return value;
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

    private record CreatePlayerRequest(long playerId, String playerName) {
    }

    private record LogoutPlayerRequest(String playerId) {
    }
}
