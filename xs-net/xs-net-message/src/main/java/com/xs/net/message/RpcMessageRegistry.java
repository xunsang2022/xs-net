package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.List;

public interface RpcMessageRegistry {
    RpcMessageSpec<? extends MessageLite> find(int rpcNum);

    RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass);

    List<RpcMessageSpec<? extends MessageLite>> all();

    default RpcMessageSpec<? extends MessageLite> findByDtoClass(Class<?> dtoClass) {
        return all().stream()
                .filter(spec -> dtoClass.equals(spec.dtoClass()))
                .findFirst()
                .orElse(null);
    }

    default List<RpcMessageSpec<? extends MessageLite>> findByRole(Class<?> roleClass) {
        return all().stream()
                .filter(spec -> roleClass.equals(spec.roleClass()))
                .toList();
    }

    default RpcMessageSpec<? extends MessageLite> findResponseForRequest(
            RpcMessageSpec<? extends MessageLite> requestSpec
    ) {
        if (requestSpec.handler() == null || !requestSpec.handler().hasResponse()) {
            return null;
        }
        Class<?> responseType = requestSpec.handler().responseType();
        return all().stream()
                .filter(spec -> spec.direction() == RpcMessageDirection.RESPONSE)
                .filter(spec -> responseType.equals(spec.dtoClass()))
                .findFirst()
                .orElse(null);
    }
}
