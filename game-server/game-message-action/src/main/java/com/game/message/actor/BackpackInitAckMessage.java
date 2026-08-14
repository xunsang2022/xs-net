package com.game.message.actor;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.login.PlayerMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.*;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 3001)
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "BackpackInitAckMessage", filePackage = "pb.backpack")
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class BackpackInitAckMessage implements PlayerMessage {
    long playerId;
    int status;
    int itemCount;
}
