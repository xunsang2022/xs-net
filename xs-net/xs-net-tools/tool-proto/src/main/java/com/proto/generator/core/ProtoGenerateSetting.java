package com.proto.generator.core;

import java.util.function.Function;

/**
 * Proto生成设置
 * 
 * @author  https://github.com/xunsang2022
 */
public class ProtoGenerateSetting {
    
    /** 是否启用日志 */
    public static boolean enableLog = true;
    
    /** 字段名生成函数 */
    private static Function<FieldNameGenerate, String> fieldNameFunction = fieldNameGenerate -> fieldNameGenerate.getFieldName();
    
    /**
     * 获取字段名生成函数
     *
     * @return 字段名生成函数
     */
    public static Function<FieldNameGenerate, String> getFieldNameFunction() {
        return fieldNameFunction;
    }
    
    /**
     * 设置字段名生成函数
     *
     * @param fieldNameFunction 字段名生成函数
     */
    public static void setFieldNameFunction(Function<FieldNameGenerate, String> fieldNameFunction) {
        if (fieldNameFunction != null) {
            ProtoGenerateSetting.fieldNameFunction = fieldNameFunction;
        }
    }
} 