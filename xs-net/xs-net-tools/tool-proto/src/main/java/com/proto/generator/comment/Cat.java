package com.proto.generator.comment;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.proto.generator.annotation.ProtoFileMerge;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

/**
 * 猫
 */
@ProtobufClass
@FieldDefaults(level = AccessLevel.PUBLIC)
@ProtoFileMerge(fileName = MyProtoFile.fileName, filePackage = MyProtoFile.filePackage)
public class Cat  {
    /** id */
    int id;
    /** 猫的名字 */
    String catName;
    /** 食物 map */
    Map<Integer, Integer> foodMap;
    /** 道具 id 列表 */
    List<Long> propIdList;
}

