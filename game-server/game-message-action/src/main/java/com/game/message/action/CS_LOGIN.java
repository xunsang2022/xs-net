package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.logic.PlayerMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

/**
 * C2S 1000 登录
 */
@Setter
@ActionMessage(value = 1000)
@Getter
@ToString
@ProtobufClass
@ProtoFileMerge(fileName = "CS_LOGIN", filePackage = "pb.user")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CS_LOGIN implements PlayerMessage {
    /**
     * 玩家ID
     */
    String playerId;
    /**
     * 玩家名称
     */
    String playerName;

    String sessionId;
}
