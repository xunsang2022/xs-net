package com.xs.net.rpc.maven;

import java.util.List;

record RpcModel(List<MessageModel> messages, List<NestedMessageModel> nestedMessages, List<ConverterModel> converters,
                List<GeneratedRecordModel> generatedRecords) {
}

record NestedMessageModel(String name, String dtoType, List<FieldModel> fields) {
}

/** 由 @ProtoRecord 声明的协议记录生成结果。 */
record GeneratedRecordModel(String packageName, String typeName, String source) {
}

/** 由 @ProtoRecord 声明的源类型到协议记录类型的转换。 */
record ConverterModel(String sourceType, String targetType, String targetName, List<ConverterFieldModel> fields) {
}

record ConverterFieldModel(String name, String access) {
}

record MessageModel(
        int number,
        String name,
        String dtoType,
        List<FieldModel> fields,
        String roleType,
        Direction direction,
        boolean publicVisible,
        String actorType,
        String handlerComponent,
        String handlerMethod,
        String responseDtoType,
        /** 注解声明的 RPC 类型：MESSAGE、INTERNAL、ACTOR_MODEL。 */
        String kind
) {
}

record FieldModel(int number, String name, String javaType, String nestedType, String messageType, String messageCodec) {
}

enum Direction {
    INTERNAL,
    HTTP_RESPONSE
}
