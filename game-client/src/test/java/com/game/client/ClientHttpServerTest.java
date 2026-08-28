package com.game.client;

import com.game.protocol.pb.CS_Logout;
import com.xs.net.message.pb.NetMessage;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientHttpServerTest {

    @Test
    void forwardsHttpPayloadToSpecifiedNettySession() throws Exception {
        try (ServerSocket nettyServer = new ServerSocket()) {
            nettyServer.bind(new InetSocketAddress("127.0.0.1", 0));
            CompletableFuture<NetMessage> received = CompletableFuture.supplyAsync(() -> readMessage(nettyServer));
            GameClientApplication.ClientSettings settings = new GameClientApplication.ClientSettings(
                    "127.0.0.1", 0, "127.0.0.1", nettyServer.getLocalPort(), Duration.ofSeconds(2));
            try (ClientHttpServer server = new ClientHttpServer(settings)) {
                server.start();
                HttpClient httpClient = HttpClient.newHttpClient();
                URI baseUri = URI.create("http://127.0.0.1:" + server.port());

                HttpResponse<String> connect = post(httpClient, baseUri, "/sessions/session-1/connect", "{\"token\":\"token-1\"}");
                assertEquals(201, connect.statusCode());

                HttpResponse<String> send = post(
                        httpClient,
                        baseUri,
                        "/sessions/session-1/messages",
                        "{\"rpcNum\":1001,\"payload\":{\"playerId\":\"42\",\"sessionId\":\"session-1\"}}");
                assertEquals(202, send.statusCode());

                NetMessage netMessage = received.get(2, TimeUnit.SECONDS);
                assertEquals(1001, netMessage.getRpcNum());
                assertEquals("42", CS_Logout.parseFrom(netMessage.getData()).getPlayerId());
            }
        }
    }

    private static HttpResponse<String> post(HttpClient client, URI baseUri, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static NetMessage readMessage(ServerSocket server) {
        try (Socket socket = server.accept(); DataInputStream input = new DataInputStream(socket.getInputStream())) {
            NettyFrameCodec.read(input);
            return NetMessage.parseFrom(NettyFrameCodec.read(input));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
