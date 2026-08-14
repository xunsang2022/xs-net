package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.logic.C2S;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 2002)
@Data
@ToString
@ProtobufClass
@ProtoFileMerge(fileName = "CS_USE_ITEM", filePackage = "pb.backpack")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CS_USE_ITEM implements C2S {
    String sessionId;
    Long itemId;
    Long count;
}
