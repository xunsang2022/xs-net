package com.proto.generator.comment;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults; /**
 * 动物
 */
@ProtobufClass
@FieldDefaults(level = AccessLevel.PUBLIC)
@ProtoFileMerge(fileName = MyProtoFile.fileName, filePackage = MyProtoFile.filePackage)
public class Animal {
    /** id */
    int id;
    /** 动物类型 - 枚举测试 */
    AnimalType animalType;
}
