package com.proto.generator.core;

import com.xs.commons.lang4.util.ArrayKit;
import com.xs.commons.lang4.util.FileKit;
import com.xs.commons.lang4.util.StrKit;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.jctools.maps.NonBlockingHashMap;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * 生成proto文件的核心类
 * 
 * @author  https://github.com/xunsang2022
 */
@Slf4j
@Setter
@Accessors(chain = true)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ProtoGenerateFile {
    
    /** proto package path */
    final Set<String> protoPackageSet = new HashSet<>();
    /** pb 源码目录 */
    String protoSourcePath;
    /** 生成 proto file 目录 */
    String generateFolder;

    public ProtoGenerateFile addProtoPackage(Collection<String> protoPackageList) {
        this.protoPackageSet.addAll(protoPackageList);
        return this;
    }

    public ProtoGenerateFile addProtoPackage(String packageName) {
        protoPackageSet.add(packageName);
        return this;
    }

    private void checked() {
        if (StrKit.isBlank(generateFolder)) {
            String currentDir = System.getProperty("user.dir");
            this.generateFolder = ArrayKit.join(new String[]{currentDir, "target", "proto"}, File.separator);
        }

        FileKit.mkdir(this.generateFolder);

        if (Objects.isNull(this.protoSourcePath)) {
            this.protoSourcePath = System.getProperty("user.dir");
        }

        if (protoPackageSet.isEmpty()) {
            throw new RuntimeException("protoPackageSet is empty");
        }
    }

    public void generate() {
        checked();

        ProtoJavaAnalyse.getJavaProjectBuilder(protoSourcePath);

        ProtoJavaAnalyse analyse = new ProtoJavaAnalyse();
        Map<ProtoJavaRegionKey, ProtoJavaRegion> regionMap = new NonBlockingHashMap<>();

        protoPackageSet.parallelStream().forEach(protoPackage -> {
            // analyse protoPackage
            regionMap.putAll(analyse.analyse(protoPackage, protoSourcePath));
        });

        Consumer<ProtoJavaRegion> javaRegionConsumer = javaRegion -> {
            var fileName = javaRegion.getFileName();
            var protoJavaList = javaRegion.getProtoJavaList();

            String protoString = javaRegion.toProtoFile();

            if (ProtoGenerateSetting.enableLog) {
                log.info("""
                        ########## {} ########## protoSize:{}
                        {}
                        """, fileName, protoJavaList.size(), protoString);
            }

            String protoFilePath = StrKit.format("{}{}{}"
                    , this.generateFolder
                    , File.separator
                    , fileName
            );

            // 确保文件名以.proto结尾
            if (!protoFilePath.endsWith(".proto")) {
                protoFilePath += ".proto";
            }

            FileKit.writeUtf8String(protoString, protoFilePath);
            log.info("\nprotoFilePath: {}", protoFilePath);
        };

        regionMap.values().forEach(javaRegionConsumer);
    }
} 