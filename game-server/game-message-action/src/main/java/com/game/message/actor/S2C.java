package com.game.message.actor;

import com.xs.kernel.actor.node.Roles;
import com.xs.net.message.INetMessage;

@Roles("gateway")
public interface S2C extends INetMessage {
}
