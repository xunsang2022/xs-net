package com.xs.net.rpc.maven;

import java.util.List;

record RpcModel(List<MessageModel> messages) {
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
        String handlerMethod,
        String responseDtoType
) {
}

record FieldModel(int number, String name, String javaType) {
}

enum Direction {
    REQUEST,
    RESPONSE,
    INTERNAL,
    HTTP_RESPONSE
}
