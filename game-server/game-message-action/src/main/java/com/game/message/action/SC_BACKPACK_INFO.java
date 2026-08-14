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

import java.util.List;

@ActionMessage(value = 2001)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "SC_BACKPACK_INFO", filePackage = "pb.backpack")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SC_BACKPACK_INFO implements S2C {
    int result;
    List<BackpackItemInfo> items;
    String sessionId;
}
