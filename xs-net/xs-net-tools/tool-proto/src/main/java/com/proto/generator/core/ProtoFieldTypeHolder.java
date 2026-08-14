package com.proto.generator.core;

import lombok.AccessLevel;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

import java.util.HashMap;
import java.util.Map;

/**
 * Proto字段类型映射器
 * 
 * @author  https://github.com/xunsang2022
 */
@FieldDefaults(level = AccessLevel.PRIVATE)
@Accessors(chain = true)
public class ProtoFieldTypeHolder {

    final Map<Class<?>, String> fieldTypeMap = new HashMap<>();

    /**
     * 获取proto类型
     *
     * @param fieldTypeClass Java类型
     * @return proto类型字符串
     */
    public String getProtoType(Class<?> fieldTypeClass) {
        return fieldTypeMap.get(fieldTypeClass);
    }

    /**
     * 初始化类型映射
     */
    private void init() {
        fieldTypeMap.put(Double.class, "double");
        fieldTypeMap.put(double.class, "double");

        fieldTypeMap.put(Float.class, "float");
        fieldTypeMap.put(float.class, "float");

        fieldTypeMap.put(Long.class, "int64");
        fieldTypeMap.put(long.class, "int64");

        fieldTypeMap.put(Integer.class, "int32");
        fieldTypeMap.put(int.class, "int32");

        fieldTypeMap.put(Boolean.class, "bool");
        fieldTypeMap.put(boolean.class, "bool");

        fieldTypeMap.put(String.class, "string");

        fieldTypeMap.put(byte[].class, "bytes");
    }

    public ProtoFieldTypeHolder() {
        init();
    }

    /**
     * 获取单例实例
     *
     * @return ProtoFieldTypeHolder实例
     */
    public static ProtoFieldTypeHolder me() {
        return Holder.ME;
    }

    /** 通过 JVM 的类加载机制, 保证只加载一次 (singleton) */
    private static class Holder {
        static final ProtoFieldTypeHolder ME = new ProtoFieldTypeHolder();
    }
} 