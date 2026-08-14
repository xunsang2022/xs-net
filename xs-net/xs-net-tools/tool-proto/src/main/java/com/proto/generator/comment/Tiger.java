package com.proto.generator.comment;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults; /**
 * 老虎
 */
@ProtobufClass
@FieldDefaults(level = AccessLevel.PUBLIC)
@ProtoFileMerge(fileName = MyProtoFile.fileName, filePackage = MyProtoFile.filePackage)
public class Tiger {
    /** id */
    int id;
    /** 食物 */
    Food food;
}
