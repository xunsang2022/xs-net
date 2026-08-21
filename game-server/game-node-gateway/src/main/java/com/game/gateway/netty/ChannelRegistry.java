package com.game.gateway.netty;

import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentHashMap;

/**
 * channelId ↔ Netty Channel 映射注册表。
 * 连接建立（认证通过）时注册，连接断开时清理。
 */
public final class ChannelRegistry {

    public static final ChannelRegistry INSTANCE = new ChannelRegistry();

    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();

    private ChannelRegistry() {
    }

    public void register(String channelId, Channel channel) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        channels.put(channelId, channel);
    }

    public Channel get(String channelId) {
        return channelId == null ? null : channels.get(channelId);
    }

    public Channel remove(String channelId) {
        return channelId == null ? null : channels.remove(channelId);
    }

    public boolean isRegistered(String channelId) {
        return channelId != null && channels.containsKey(channelId);
    }

    public int size() {
        return channels.size();
    }
}