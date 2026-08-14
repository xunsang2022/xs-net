package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.game.message.actor.S2C;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * 客户端日志上报响应
 */
/// 2101
@ActionMessage(value = 2101)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@ProtobufClass
@ProtoFileMerge(fileName = "SC_LOG_REPORT", filePackage = "pb.log")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SC_LOG_REPORT implements S2C {
    String sessionId;
    
    /**
     * 结果：0-成功，1-失败
     */
    int result;
    
    /**
     * 日志 ID（成功时返回）
     */
    Long logId;
}
