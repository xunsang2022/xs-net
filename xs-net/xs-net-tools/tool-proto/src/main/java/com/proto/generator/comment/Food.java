package com.proto.generator.comment;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults; /**
 * 食物
 */
@ProtobufClass
@FieldDefaults(level = AccessLevel.PUBLIC)
@ProtoFileMerge(fileName = MyProtoFile.fileName, filePackage = MyProtoFile.filePackage)
public class Food {
    /** id */
    int id;
    /** 食物名 */
    String foodName;
}
