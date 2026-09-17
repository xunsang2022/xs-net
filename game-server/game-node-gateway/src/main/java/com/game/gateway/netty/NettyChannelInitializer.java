package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;

/**
 * Netty 管道装配：长度前缀拆帧 + 心跳检测 + 认证/上行消息处理。
 * <p>
 * 关键点：
 * <ul>
 *   <li>{@link LengthFieldBasedFrameDecoder}：在 I/O 线程完成拆帧（轻量）；</li>
 *   <li>{@link IdleStateHandler}：读空闲超时检测，识别半开连接并交给上层踢出；</li>
 *   <li>{@code AuthHandler}：挂载到 {@code businessExecutor} 业务线程池，把验签/解码/投递等
 *       CPU 密集逻辑移出 I/O 线程，避免阻塞事件循环。</li>
 * </ul>
 */
@RequiredArgsConstructor
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {

    /** JWT 认证工具。 */
    @NonNull
    private final JwtUtil jwtUtil;
    /** 承载验签、解码和 RPC 投递的业务线程组，不能使用 Netty EventLoop 阻塞执行。 */
    @NonNull
    private final EventExecutorGroup businessExecutor;
    /** TCP 帧、空闲检测和认证超时配置。 */
    @NonNull
    private final NettyConfig config;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new LengthFieldBasedFrameDecoder(
                config.getMaxFrameLength(),
                NettyFramer.LENGTH_FIELD_OFFSET,
                NettyFramer.LENGTH_FIELD_LENGTH,
                NettyFramer.LENGTH_ADJUSTMENT,
                NettyFramer.INITIAL_BYTES_TO_STRIP));

        if (config.getReaderIdleTimeoutSeconds() > 0) {
            // 读空闲检测：在 I/O 线程执行，超时触发 IdleStateEvent 由 AuthHandler 处理踢出
            pipeline.addLast(new IdleStateHandler(
                    config.getReaderIdleTimeoutSeconds(), 0, 0, TimeUnit.SECONDS));
        }

        pipeline.addLast(businessExecutor, new AuthHandler(jwtUtil, config));
    }
}
