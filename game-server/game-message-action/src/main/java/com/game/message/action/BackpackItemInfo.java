package com.game.message.action;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ProtobufClass
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BackpackItemInfo {
    Long itemId;
    Long count;
}
