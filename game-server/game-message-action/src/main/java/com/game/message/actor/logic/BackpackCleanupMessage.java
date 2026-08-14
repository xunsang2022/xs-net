package com.game.message.actor.logic;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.*;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 3002)
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "BackpackCleanupMessage", filePackage = "pb.backpack")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BackpackCleanupMessage implements C2S {
    long playerId;
}
