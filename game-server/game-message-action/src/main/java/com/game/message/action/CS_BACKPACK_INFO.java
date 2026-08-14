package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.logic.C2S;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.*;
import lombok.experimental.FieldDefaults;

/// 2000
@ActionMessage(value = 2000)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@ProtobufClass
@ProtoFileMerge(fileName = "CS_BACKPACK_INFO", filePackage = "pb.backpack")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CS_BACKPACK_INFO implements C2S {
    String sessionId;
}
