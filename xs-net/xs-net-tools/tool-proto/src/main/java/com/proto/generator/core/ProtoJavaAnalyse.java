package com.proto.generator.core;

import com.baidu.bjf.remoting.protobuf.EnumReadable;
import com.baidu.bjf.remoting.protobuf.annotation.Ignore;
import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.esotericsoftware.reflectasm.FieldAccess;
import com.proto.generator.annotation.ProtoFileMerge;
import com.xs.commons.lang4.util.ClassScanner;
import com.xs.commons.lang4.util.StrKit;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import lombok.extern.slf4j.Slf4j;
import org.jctools.maps.NonBlockingHashMap;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Proto Java分析器
 * 
 * @author  https://github.com/xunsang2022
 */
@Slf4j
public class ProtoJavaAnalyse {
    
    static final Map<String, JavaProjectBuilder> javaProjectBuilderMap = new NonBlockingHashMap<>();
    final Map<ProtoJavaRegionKey, ProtoJavaRegion> protoJavaRegionMap = new NonBlockingHashMap<>();
    final Map<Class<?>, ProtoJava> protoJavaMap = new NonBlockingHashMap<>();
    final Map<String, JavaClass> protoJavaSourceFileMap = new NonBlockingHashMap<>();
    
    /** 默认的类过滤器 */
    final Predicate<Class<?>> predicateFilter = clazz -> {
        ProtobufClass protobufClass = clazz.getAnnotation(ProtobufClass.class);
        ProtoFileMerge protoFileMerge = clazz.getAnnotation(ProtoFileMerge.class);
        return Objects.nonNull(protobufClass) && Objects.nonNull(protoFileMerge);
    };

    public Map<ProtoJavaRegionKey, ProtoJavaRegion> analyse(String protoPackagePath, String protoSourcePath) {
        return this.analyse(protoPackagePath, protoSourcePath, this.predicateFilter);
    }

    public Map<ProtoJavaRegionKey, ProtoJavaRegion> analyse(String protoPackagePath, String protoSourcePath, Predicate<Class<?>> predicateFilter) {
        var javaProjectBuilder = getJavaProjectBuilder(protoSourcePath);
        Collection<JavaClass> javaClassCollection = javaProjectBuilder.getClasses();

        javaClassCollection.parallelStream().filter(javaClass -> {
            List<JavaAnnotation> annotations = javaClass.getAnnotations();
            if (annotations.size() < 2) {
                return false;
            }

            long count = annotations.parallelStream().filter(annotation -> {
                String string = annotation.getType().toString();
                return string.contains(ProtobufClass.class.getName())
                       || string.contains(ProtoFileMerge.class.getName());
            }).count();

            return count >= 2;
        }).forEach(javaClass -> {
            protoJavaSourceFileMap.put(javaClass.toString(), javaClass);

            if (ProtoGenerateSetting.enableLog) {
                log.info("javaClass: {}", javaClass);
            }
        });

        ClassScanner classScanner = new ClassScanner(protoPackagePath, predicateFilter);
        List<Class<?>> classList = classScanner.listScan();

        if (classList.isEmpty()) {
            return protoJavaRegionMap;
        }

        List<ProtoJava> protoJavaList = this.convert(classList);
        for (ProtoJava protoJava : protoJavaList) {
            this.analyseField(protoJava);
        }

        return protoJavaRegionMap;
    }

    static JavaProjectBuilder getJavaProjectBuilder(String protoSourcePath) {
        return javaProjectBuilderMap.computeIfAbsent(protoSourcePath, path -> {
            var builder = new JavaProjectBuilder();
            builder.setEncoding(StandardCharsets.UTF_8.name());
            builder.addSourceTree(new File(path));
            return builder;
        });
    }

    private List<ProtoJava> convert(List<Class<?>> classList) {
        return classList.stream().map(clazz -> {
            ProtoFileMerge annotation = clazz.getAnnotation(ProtoFileMerge.class);
            String fileName = annotation.fileName();
            String filePackage = annotation.filePackage();
            JavaClass javaClass = protoJavaSourceFileMap.get(clazz.toString());

            ProtoJava protoJava = new ProtoJava()
                    .setClassName(clazz.getSimpleName())
                    .setComment(javaClass != null ? javaClass.getComment() : "")
                    .setClazz(clazz)
                    .setFileName(fileName)
                    .setFilePackage(filePackage)
                    .setJavaClass(javaClass);

            protoJavaMap.put(clazz, protoJava);

            ProtoJavaRegionKey regionKey = new ProtoJavaRegionKey(fileName, filePackage);
            ProtoJavaRegion protoJavaRegion = this.getProtoJavaRegion(regionKey);
            protoJavaRegion.addProtoJava(protoJava);

            return protoJava;
        }).collect(Collectors.toList());
    }

    private ProtoJavaRegion getProtoJavaRegion(ProtoJavaRegionKey regionKey) {
        return protoJavaRegionMap.computeIfAbsent(regionKey, key -> {
            ProtoJavaRegion region = new ProtoJavaRegion();
            region.setFileName(key.getFileName());
            region.setFilePackage(key.getFilePackage());
            return region;
        });
    }

    private void analyseField(ProtoJava protoJava) {
        Class<?> clazz = protoJava.getClazz();
        Field[] fields = clazz.isEnum()
                ? Arrays.stream(clazz.getDeclaredFields()).filter(field -> field.getType().isEnum()).toArray(Field[]::new)
                : FieldAccess.get(clazz).getFields();

        JavaClass javaClass = protoJava.getJavaClass();
        // 枚举 enum 的下标从 0 开始，buffInfo 的下标从 1 开始
        int order = clazz.isEnum() ? 0 : 1;
        var enumConstants = clazz.isEnum() ? clazz.getEnumConstants() : new Object[0];

        for (int i = 0; i < fields.length; i++) {
            var field = fields[i];
            if (Objects.nonNull(field.getAnnotation(Ignore.class))) {
                continue;
            }

            Class<?> fieldTypeClass = field.getType();
            String fieldName = field.getName();
            JavaField javaField = javaClass != null ? javaClass.getFieldByName(fieldName) : null;

            ProtoJavaField protoJavaField = new ProtoJavaField()
                    .setRepeated(List.class.equals(fieldTypeClass))
                    .setFieldName(fieldName)
                    .setComment(javaField != null ? javaField.getComment() : "")
                    .setOrder(order++)
                    .setFieldTypeClass(fieldTypeClass)
                    .setField(field)
                    .setProtoJavaParent(protoJava);

            // 自定义枚举值
            if (clazz.isEnum() && EnumReadable.class.isAssignableFrom(clazz)) {
                if (i < enumConstants.length && enumConstants[i] instanceof EnumReadable r) {
                    protoJavaField.setOrder(r.value());
                }
            }

            protoJava.addProtoJavaFiled(protoJavaField);

            String fieldProtoType = ProtoFieldTypeHolder.me().getProtoType(fieldTypeClass);
            if (Objects.nonNull(fieldProtoType)) {
                protoJavaField.setFieldProtoType(fieldProtoType);
                continue;
            }

            if (protoJavaField.isMap()) {
                processMapFieldProtoJava(protoJavaField);
            } else if (protoJavaField.isList()) {
                processListFieldProtoJava(protoJavaField);
            } else {
                processFieldProtoJava(protoJavaField);
            }
        }
    }

    private String fieldProtoTypeToString(ProtoJavaField protoJavaField, Class<?> fieldTypeClass) {
        String fieldName = protoJavaField.getFieldName();
        // 这个字段是一个 proto 对象类型
        ProtoJava protoJavaFieldType = this.getFieldProtoJava(fieldTypeClass, fieldName, protoJavaField);
        ProtoJava protoJavaParent = protoJavaField.getProtoJavaParent();

        String filePackage = protoJavaFieldType.getFilePackage();
        String className = protoJavaFieldType.getClassName();

        String fieldProtoType;

        if (protoJavaParent.inThisFile(protoJavaFieldType)) {
            // 同一个文件的 proto 对象
            fieldProtoType = className;
        } else {
            ProtoJavaRegionKey regionKey = protoJavaParent.getProtoJavaRegionKey();
            ProtoJavaRegion protoJavaRegion = this.getProtoJavaRegion(regionKey);
            protoJavaRegion.addOtherProtoFile(protoJavaFieldType);
            // 不在同一个文件中
            fieldProtoType = StrKit.format("{}.{}", filePackage, className);
        }

        return fieldProtoType;
    }

    private void processFieldProtoJava(ProtoJavaField protoJavaField) {
        // 这个字段是一个 proto 对象类型
        Class<?> fieldTypeClass = protoJavaField.getFieldTypeClass();
        String fieldName = protoJavaField.getFieldName();

        if (Objects.isNull(protoJavaField.getComment()) || protoJavaField.getComment().isEmpty()) {
            ProtoJava protoJavaFieldType = this.getFieldProtoJava(fieldTypeClass, fieldName, protoJavaField);
            protoJavaField.setComment(protoJavaFieldType.getComment());
        }

        String fieldProtoType = this.fieldProtoTypeToString(protoJavaField, fieldTypeClass);
        protoJavaField.setFieldProtoType(fieldProtoType);
    }

    private void processListFieldProtoJava(ProtoJavaField protoJavaField) {
        // 获取 List 的泛型类型
        ParameterizedType genericType = (ParameterizedType) protoJavaField.getField().getGenericType();
        Type[] actualTypeArguments = genericType.getActualTypeArguments();

        Class<?> firstClass = (Class<?>) actualTypeArguments[0];
        String fieldProtoType = ProtoFieldTypeHolder.me().getProtoType(firstClass);

        if (Objects.isNull(fieldProtoType)) {
            fieldProtoType = this.fieldProtoTypeToString(protoJavaField, firstClass);
        }

        protoJavaField
                .setRepeated(true)
                .setFieldProtoType(fieldProtoType);
    }

    private void processMapFieldProtoJava(ProtoJavaField protoJavaField) {
        // 获取 Map 的 <k,v> 类型
        ParameterizedType genericType = (ParameterizedType) protoJavaField.getField().getGenericType();
        Type[] actualTypeArguments = genericType.getActualTypeArguments();

        Class<?> keyClass = (Class<?>) actualTypeArguments[0];
        Class<?> valueClass = (Class<?>) actualTypeArguments[1];

        String keyProtoType = ProtoFieldTypeHolder.me().getProtoType(keyClass);
        String valueProtoType = ProtoFieldTypeHolder.me().getProtoType(valueClass);

        if (Objects.isNull(valueProtoType)) {
            valueProtoType = this.fieldProtoTypeToString(protoJavaField, valueClass);
        }

        String mapFieldType = StrKit.format("map<{}, {}>", keyProtoType, valueProtoType);
        protoJavaField.setFieldProtoType(mapFieldType);
    }

    private ProtoJava getFieldProtoJava(Class<?> fieldTypeClass, String fieldName, ProtoJavaField protoJavaField) {
        ProtoJava protoJava = protoJavaMap.get(fieldTypeClass);
        
        if (Objects.isNull(protoJava)) {
            // 如果找不到对应的ProtoJava，创建一个默认的
            ProtoFileMerge protoFileMerge = fieldTypeClass.getAnnotation(ProtoFileMerge.class);
            
            if (protoFileMerge != null) {
                protoJava = new ProtoJava()
                        .setClassName(fieldTypeClass.getSimpleName())
                        .setComment(fieldTypeClass.getSimpleName())
                        .setClazz(fieldTypeClass)
                        .setFileName(protoFileMerge.fileName())
                        .setFilePackage(protoFileMerge.filePackage());
                
                protoJavaMap.put(fieldTypeClass, protoJava);
            } else {
                // 如果没有ProtoFileMerge注解，使用默认值
                protoJava = new ProtoJava()
                        .setClassName(fieldTypeClass.getSimpleName())
                        .setComment(fieldTypeClass.getSimpleName())
                        .setClazz(fieldTypeClass)
                        .setFileName("common.proto")
                        .setFilePackage("proto.common");
                
                protoJavaMap.put(fieldTypeClass, protoJava);
            }
        }
        
        return protoJava;
    }
} 