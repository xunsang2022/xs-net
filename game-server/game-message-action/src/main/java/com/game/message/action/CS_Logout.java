package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.logic.PlayerMessage;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 1001)
@Data
@ToString
@ProtobufClass
@ProtoFileMerge(fileName = "CS_Logout", filePackage = "pb.user")
@FieldDefaults(level = AccessLevel.PRIVATE)
public
class CS_Logout implements PlayerMessage {
    String playerId;
    String sessionId;
}
