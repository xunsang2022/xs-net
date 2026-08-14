package com.game.message.actor.db;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.logic.PlayerMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Map;

@ActionMessage(value = 3102)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "DbSyncLoadResultMessage", filePackage = "pb.dbsync")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class DbSyncLoadResultMessage implements PlayerMessage, DbSyncCmd {
    Long playerId;
    boolean success;
    Map<String, String> fields;
}
