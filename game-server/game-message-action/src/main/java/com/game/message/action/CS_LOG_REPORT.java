package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.XSSystem;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * 客户端日志上报请求
 */
/// 2100
@ActionMessage(value = 2100)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@ProtobufClass
@ProtoFileMerge(fileName = "CS_LOG_REPORT", filePackage = "pb.log")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CS_LOG_REPORT implements XSSystem {
    String sessionId;
    
    /**
     * 日志级别（0-HIGH, 1-MEDIUM, 2-LOW）
     */
    int logLevel;
    
    /**
     * 日志类型（对应枚举 ordinal）
     */
    int logType;
    
    /**
     * 日志内容（JSON 字符串）
     */
    String content;
}
