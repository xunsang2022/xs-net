package com.proto.generator.comment;

import com.baidu.bjf.remoting.protobuf.EnumReadable;
import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;

/**
 * 动物类型
 */
@ProtobufClass
@ProtoFileMerge(fileName = MyProtoFile.fileName, filePackage = MyProtoFile.filePackage)
public enum AnimalType {
    /** 鸟 */
    BIRD,
    /** 猫 */
    CAT;
}

