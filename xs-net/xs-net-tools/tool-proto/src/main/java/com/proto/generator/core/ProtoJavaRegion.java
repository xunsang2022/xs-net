package com.proto.generator.core;

import com.xs.commons.lang4.util.StrKit;
import com.xs.commons.lang4.util.FormatTimeKit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import org.jctools.maps.NonBlockingHashMap;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Proto Java区域，表示一个proto文件中的所有类
 * 
 * @author  https://github.com/xunsang2022
 */
@Setter
@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
@Accessors(chain = true)
public class ProtoJavaRegion {
    
    String fileName;
    String filePackage;

    final Map<Class<?>, ProtoJava> protoJavaMap = new NonBlockingHashMap<>();
    final List<ProtoJava> protoJavaList = new CopyOnWriteArrayList<>();
    final ProtoJavaRegionHead regionHead = new ProtoJavaRegionHead();

    public void addProtoJava(ProtoJava protoJava) {
        this.protoJavaList.add(protoJava);
        this.protoJavaMap.put(protoJava.getClazz(), protoJava);
    }

    public void addOtherProtoFile(ProtoJava protoJava) {
        String fileName = protoJava.getFileName();
        this.regionHead.fileNameSet.add(fileName);
    }

    static class ProtoJavaRegionHead {
        Set<String> fileNameSet = new HashSet<>();
        String filePackage;

        private String toProtoHead() {
            String templateFileName = """
                    import "{}";
                    """;

            StringBuilder fileNameBuilder = new StringBuilder();

            for (String filePackage : fileNameSet) {
                String filePackageString = StrKit.format(templateFileName, filePackage);
                fileNameBuilder.append(filePackageString);
            }

            String templateHead = """
                    syntax = "proto3";
                    package {};
                    {}
                    """;

            return StrKit.format(templateHead, this.filePackage, fileNameBuilder.toString());
        }
    }

    public String toProtoFile() {
        this.regionHead.filePackage = this.filePackage;
        String protoHead = this.regionHead.toProtoHead();

        String s = "https://github.com/xunsang2022";
        String firstLine = """
                // generate %s , protoSize: %s
                // %s
                """.formatted(FormatTimeKit.format(), protoJavaList.size(),s);

        StringBuilder builder = new StringBuilder();
        builder.append(firstLine);
        builder.append(System.lineSeparator());
        builder.append(protoHead);

        this.protoJavaList.stream()
                // 排序规则
                .sorted(Comparator.comparing(ProtoJava::getClassName))
                .map(ProtoJava::toProtoMessage)
                .forEach(builder::append);

        return builder.toString();
    }
} 