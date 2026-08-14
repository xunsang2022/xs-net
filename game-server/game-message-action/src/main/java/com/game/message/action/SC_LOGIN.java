package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.xs.net.message.annotation.ActionMessage;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * S2C 1002 登录响应
 */
@ActionMessage(value = 1002)
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@ProtoFileMerge(fileName = "SC_LOGIN", filePackage = "pb.user")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SC_LOGIN {
    /**
     * 登录结果：0-成功，1-失败
     */
    int result;

    /**
     * 玩家ID
     */
    String playerId;

    /**
     * 玩家名称
     */
    String playerName;

    /**
     * JWT token，客户端后续请求需携带
     */
    String token;
}
