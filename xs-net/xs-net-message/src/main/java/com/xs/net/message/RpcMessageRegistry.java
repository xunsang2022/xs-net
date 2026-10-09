package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.List;

/**
 * RPC消息注册表接口，管理所有RPC消息规范。
 * 提供按RPC编号、消息类、DTO类和角色类型查找消息规范的功能。
 */
public interface RpcMessageRegistry {
    /**
     * 根据RPC编号查找消息规范。
     *
     * @param rpcNum RPC编号
     * @return 消息规范，如果未找到返回null
     */
    RpcMessageSpec<? extends MessageLite> find(int rpcNum);

    /**
     * 根据消息类查找消息规范。
     *
     * @param messageClass Protocol Buffers消息类
     * @return 消息规范，如果未找到返回null
     */
    RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass);

    /**
     * 获取所有消息规范。
     *
     * @return 消息规范列表
     */
    List<RpcMessageSpec<? extends MessageLite>> all();

    /**
     * 根据DTO类查找消息规范。
     *
     * @param dtoClass DTO类
     * @return 消息规范，如果未找到返回null
     */
    default RpcMessageSpec<? extends MessageLite> findByDtoClass(Class<?> dtoClass) {
        return all().stream()
                .filter(spec -> dtoClass.equals(spec.dtoClass()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据角色类型查找消息规范。
     *
     * @param roleClass 角色类
     * @return 消息规范列表
     */
    default List<RpcMessageSpec<? extends MessageLite>> findByRole(Class<?> roleClass) {
        return all().stream()
                .filter(spec -> roleClass.equals(spec.roleClass()))
                .toList();
    }

    /**
     * 查找请求消息对应的响应消息规范。
     *
     * @param requestSpec 请求消息规范
     * @return 响应消息规范，如果未找到返回null
     */
    default RpcMessageSpec<? extends MessageLite> findResponseForRequest(
            RpcMessageSpec<? extends MessageLite> requestSpec
    ) {
        if (requestSpec.handler() == null || !requestSpec.handler().hasResponse()) {
            return null;
        }
        Class<?> responseType = requestSpec.handler().responseType();
        return all().stream()
                .filter(spec -> responseType.equals(spec.dtoClass()))
                .filter(spec -> spec.direction() == RpcMessageDirection.HTTP_RESPONSE)
                .findFirst()
                .orElse(null);
    }
}
