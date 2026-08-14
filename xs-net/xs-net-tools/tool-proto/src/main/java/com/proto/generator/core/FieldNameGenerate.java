package com.proto.generator.core;

import lombok.Data;

/**
 * 字段名生成器
 * 
 * @author  https://github.com/xunsang2022
 */
@Data
public class FieldNameGenerate {
    
    /** 字段名 */
    private String fieldName;
    
    /** 是否为枚举类型 */
    private boolean enumType;
} 