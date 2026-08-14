package com.proto.generator.core;

import com.xs.commons.lang4.util.StrKit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Proto Java字段信息
 * 
 * @author  https://github.com/xunsang2022
 */
@Getter
@Setter
@FieldDefaults(level = AccessLevel.PRIVATE)
@Accessors(chain = true)
public class ProtoJavaField {
    
    boolean repeated;
    String fieldName;
    String comment;
    int order;
    Class<?> fieldTypeClass;
    String fieldProtoType;
    Field field;
    
    ProtoJava protoJavaParent;

    boolean isMap() {
        return Map.class.equals(fieldTypeClass);
    }

    boolean isList() {
        return List.class.equals(fieldTypeClass);
    }

    private Map<String, String> createParam() {
        Map<String, String> messageMap = new HashMap<>();

        messageMap.put("comment", this.comment);
        messageMap.put("repeated", "");
        messageMap.put("fieldProtoType", this.fieldProtoType);
        messageMap.put("order", String.valueOf(this.order));

        FieldNameGenerate fieldNameGenerate = new FieldNameGenerate();
        fieldNameGenerate.setEnumType(this.protoJavaParent.getClazz().isEnum());
        fieldNameGenerate.setFieldName(this.fieldName);
        messageMap.put("fieldName", ProtoGenerateSetting.getFieldNameFunction().apply(fieldNameGenerate));

        if (this.repeated) {
            messageMap.put("repeated", "repeated ");
        }

        return messageMap;
    }

    public String toProtoFieldLine() {
        Map<String, String> messageMap = this.createParam();

        String templateFiled = getTemplateFiled(this.protoJavaParent.getClazz().isEnum());

        return StrKit.format(templateFiled, messageMap);
    }

    /**
     * 生成proto文本模板
     *
     * @param fieldIsInEnum 该bool含义表示当前Field所在的类文件是否是枚举类型，取得是protoJavaParent的isEnum
     * @return 如果是枚举类文件中，属性前面不用加{fieldProtoType}，如果枚举类型是在类文件中则加上{fieldProtoType}
     */
    private String getTemplateFiled(boolean fieldIsInEnum) {
        StringBuilder templateFiled = new StringBuilder();

        if (!Objects.isNull(this.comment)) {
            templateFiled.append("""
                      // {comment}
                    """);
        }

        if (fieldIsInEnum) {
            templateFiled.append("  {repeated}{fieldName} = {order};");
        } else {
            templateFiled.append("  {repeated}{fieldProtoType} {fieldName} = {order};");
        }

        return templateFiled.toString();
    }
} 