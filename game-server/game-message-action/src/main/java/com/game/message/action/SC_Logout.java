package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.S2C;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 1003)
@Data
@ToString
@ProtobufClass
@ProtoFileMerge(fileName = "CS_Logout", filePackage = "pb.user")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SC_Logout implements S2C {
    String playerId;

    String sessionId;
}
