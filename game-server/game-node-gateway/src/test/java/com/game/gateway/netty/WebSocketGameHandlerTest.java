package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import com.xs.net.message.pb.NetMessage;
import com.xs.net.transport.PacketId;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketGameHandlerTest {

    private static final String SECRET = "xs-net-websocket-test-secret-key-32bytes";
    private static final String CHANNEL_ID = "websocket-test-channel";

    @AfterEach
    void cleanRegistry() {
        ChannelRegistry.INSTANCE.remove(CHANNEL_ID);
    }

    @Test
    void authenticatesTheFirstBinaryFrameAndRegistersWebSocketEndpoint() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000);
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketGameHandler(jwtUtil, nettyConfig()));

        channel.writeInbound(hello(jwtUtil.generateToken("player-1", CHANNEL_ID)));
        assertTrue(ChannelRegistry.INSTANCE.isActive(CHANNEL_ID));
        assertInstanceOf(WebSocketClientEndpoint.class, ChannelRegistry.INSTANCE.get(CHANNEL_ID));

        channel.finishAndReleaseAll();
        assertNull(ChannelRegistry.INSTANCE.get(CHANNEL_ID));
    }

    @Test
    void invalidFirstBinaryFrameClosesTheConnectionWithoutRegisteringIt() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketGameHandler(new JwtUtil(SECRET, 60_000), nettyConfig()));

        channel.writeInbound(hello("not-a-jwt"));
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertNull(ChannelRegistry.INSTANCE.get(CHANNEL_ID));
    }

    @Test
    void endpointWritesRawNetMessageInABinaryWebSocketFrame() {
        EmbeddedChannel channel = new EmbeddedChannel();
        WebSocketClientEndpoint endpoint = new WebSocketClientEndpoint(channel);
        NetMessage message = NetMessage.newBuilder().setRpcNum(1002).build();

        endpoint.write(message);

        BinaryWebSocketFrame frame = channel.readOutbound();
        assertTrue(frame.content().readableBytes() > 0);
        frame.release();
        channel.finishAndReleaseAll();
    }

    private static BinaryWebSocketFrame binary(String value) {
        return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static BinaryWebSocketFrame hello(String value) {
        return new BinaryWebSocketFrame(NettyFramer.encode(PacketId.HELLO, 0, 42,
                value.getBytes(StandardCharsets.UTF_8)));
    }

    private static NettyConfig nettyConfig() {
        return new NettyConfig(8082, 1, 1, 1, 1024, 65536, 10, 1, 300, 32768, 65536);
    }
}
