package com.game.message.actor.db;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@ActionMessage(value = 3100)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "DbSyncMessage", filePackage = "pb.dbsync")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class DbSyncMessage implements DbSyncCmd {
    Long playerId;
    DbSyncOp op;
}
