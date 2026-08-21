package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Netty 服务器生命周期管理：Spring 启动后拉起，关闭时停止。
 */
@Component
public class NettyServerLifecycle implements ApplicationRunner, DisposableBean {

    private final NettyServer nettyServer;

    public NettyServerLifecycle(
            @Value("${game.netty.port:8082}") int port,
            JwtUtil jwtUtil
    ) {
        this.nettyServer = new NettyServer(port, jwtUtil);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        nettyServer.start();
    }

    @Override
    public void destroy() {
        nettyServer.stop();
    }
}