package com.game.message.actor.logic;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.*;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 3000)
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "BackpackInitMessage", filePackage = "pb.backpack")
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class BackpackInitMessage implements C2S {
    long playerId;
    long timestamp;
}
