package com.game.message.actor.logic;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 3003)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "PlayerEnter", filePackage = "pb.internal")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PlayerEnter implements PlayerMessage {
    Long playerId;
    String playerName;
    String channelId;
}
