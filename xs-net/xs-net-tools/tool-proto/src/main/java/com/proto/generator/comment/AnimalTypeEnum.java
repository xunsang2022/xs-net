package com.proto.generator.comment;

import com.baidu.bjf.remoting.protobuf.EnumReadable;
import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;

@ProtobufClass
@ProtoFileMerge(fileName = MyProtoFile.fileName, filePackage = MyProtoFile.filePackage)
public enum AnimalTypeEnum implements EnumReadable {
    /** the cat */
    cat(0),
    /** the tiger */
    tiger(10),
    ;

    final int value;

    AnimalTypeEnum(int value) {
        this.value = value;
    }

    @Override
    public int value() {
        return this.value;
    }
}
