package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Netty 管道装配：长度前缀拆帧 + 认证/上行消息处理。
 */
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final JwtUtil jwtUtil;

    public NettyChannelInitializer(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new LengthFieldBasedFrameDecoder(
                NettyFramer.MAX_FRAME_LENGTH,
                NettyFramer.LENGTH_FIELD_OFFSET,
                NettyFramer.LENGTH_FIELD_LENGTH,
                NettyFramer.LENGTH_ADJUSTMENT,
                NettyFramer.INITIAL_BYTES_TO_STRIP));
        pipeline.addLast(new AuthHandler(jwtUtil));
    }
}