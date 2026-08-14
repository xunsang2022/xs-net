package com.proto.generator.core;

import com.xs.commons.lang4.util.StrKit;
import com.thoughtworks.qdox.model.JavaClass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Proto Java类信息
 * 
 * @author  https://github.com/xunsang2022
 */
@Getter
@Setter
@FieldDefaults(level = AccessLevel.PRIVATE)
@Accessors(chain = true)
public class ProtoJava {
    
    Class<?> clazz;
    String className;
    String comment;
    String fileName;
    String filePackage;
    JavaClass javaClass;
    
    List<ProtoJavaField> protoJavaFieldList = new ArrayList<>();

    public void addProtoJavaFiled(ProtoJavaField protoJavaField) {
        this.protoJavaFieldList.add(protoJavaField);
    }

    public boolean inThisFile(ProtoJava protoJava) {
        return Objects.equals(this.fileName, protoJava.fileName) && Objects.equals(this.filePackage, protoJava.filePackage);
    }

    public ProtoJavaRegionKey getProtoJavaRegionKey() {
        return new ProtoJavaRegionKey(this.fileName, this.filePackage);
    }

    public String toProtoMessage() {
        String fieldsString = protoJavaFieldList
                .stream()
                .map(ProtoJavaField::toProtoFieldLine)
                .collect(Collectors.joining("\n"));

        Map<String, String> messageMap = new HashMap<>();
        messageMap.put("classComment", this.comment);

        messageMap.put("classOrEnum", clazz.isEnum() ? "enum" : "message");

        messageMap.put("className", this.className);
        messageMap.put("fieldsString", fieldsString);

        String template = """
                // {classComment}
                {classOrEnum} {className} {
                {fieldsString}
                }
                
                """;

        return StrKit.format(template, messageMap);
    }
} 