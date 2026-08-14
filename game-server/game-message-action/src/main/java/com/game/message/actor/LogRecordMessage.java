package com.game.message.actor;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.net.message.annotation.ActionMessage;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * 内部日志记录消息（Actor 之间使用）
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ActionMessage(value = 999)
@ProtoFileMerge(fileName = "LogRecordMessage", filePackage = "pb.log")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LogRecordMessage implements XSSystem {
    /**
     * 日志级别（0-HIGH, 1-MEDIUM, 2-LOW）
     */
    int level;
    
    /**
     * 日志类型（0-LOGIN, 1-BATTLE, 2-TRADE, 3-CHAT, 4-SYSTEM, 5-OTHER）
     */
    int type;
    
    /**
     * 日志内容
     */
    String message;
    
    /**
     * 玩家 ID
     */
    Long playerId;
}
