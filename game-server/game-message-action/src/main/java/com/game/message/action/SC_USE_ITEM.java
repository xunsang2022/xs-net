package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.S2C;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 2003)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "SC_USE_ITEM", filePackage = "pb.backpack")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SC_USE_ITEM implements S2C {
    int result;
    Long itemId;
    Long remainCount;
    String sessionId;
}
