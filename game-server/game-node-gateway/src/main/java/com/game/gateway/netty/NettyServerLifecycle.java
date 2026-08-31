package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import com.game.gateway.config.UdpConfig;
import com.game.gateway.config.WebSocketConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 网络服务生命周期管理：Spring 启动后拉起 TCP 与 UDP 服务器，关闭时一并停止。
 */
@Component
public class NettyServerLifecycle implements ApplicationRunner, DisposableBean {

    private final NettyServer nettyServer;
    private final UdpServer udpServer;
    private final WebSocketServer webSocketServer;

    public NettyServerLifecycle(NettyConfig nettyConfig, UdpConfig udpConfig, WebSocketConfig webSocketConfig, JwtUtil jwtUtil) {
        this.nettyServer = new NettyServer(nettyConfig, jwtUtil);
        this.udpServer = new UdpServer(udpConfig, jwtUtil);
        this.webSocketServer = new WebSocketServer(nettyConfig, webSocketConfig, jwtUtil);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        nettyServer.start();
        udpServer.start();
        webSocketServer.start();
    }

    @Override
    public void destroy() {
        nettyServer.stop();
        udpServer.stop();
        webSocketServer.stop();
    }
}
