package com.proto.generator;

import com.proto.generator.core.ProtoGenerateFile;
import com.xs.commons.lang4.util.ArrayKit;
import com.xs.commons.lang4.util.StrKit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * Proto文件生成器
 * 
 * @author  https://github.com/xunsang2022
 */
@Slf4j
@Data
public class ProtoGenerator {
    
    /** 默认生成目录 */
    private static final String DEFAULT_GENERATE_FOLDER = "target/proto";
    
    /** proto类所在包路径 */
    private String protoPackagePath;
    
    /** 生成目录 */
    private String generateFolder;
    
    /** 源码目录 */
    private String protoSourcePath;
    
    public ProtoGenerator() {
        String currentDir = System.getProperty("user.dir");
        this.protoSourcePath = currentDir;
        this.generateFolder = ArrayKit.join(new String[]{
                currentDir, DEFAULT_GENERATE_FOLDER
        }, File.separator);
    }
    
    /**
     * 设置proto类所在包路径
     *
     * @param protoPackagePath 包路径
     * @return this
     */
    public ProtoGenerator setProtoPackagePath(String protoPackagePath) {
        this.protoPackagePath = protoPackagePath;
        return this;
    }
    
    /**
     * 设置生成目录
     *
     * @param generateFolder 生成目录
     * @return this
     */
    public ProtoGenerator setGenerateFolder(String generateFolder) {
        this.generateFolder = generateFolder;
        return this;
    }
    
    /**
     * 设置源码目录
     *
     * @param protoSourcePath 源码目录
     * @return this
     */
    public ProtoGenerator setProtoSourcePath(String protoSourcePath) {
        this.protoSourcePath = protoSourcePath;
        return this;
    }
    
    /**
     * 开始生成proto文件
     */
    public void generate() {
        if (StrKit.isBlank(protoPackagePath)) {
            log.error("protoPackagePath 不能为空");
            return;
        }
        
        log.info("开始生成proto文件...");
        log.info("包路径: {}", protoPackagePath);
        log.info("源码目录: {}", protoSourcePath);
        log.info("生成目录: {}", generateFolder);
        
        try {
            ProtoGenerateFile protoGenerateFile = new ProtoGenerateFile();
            protoGenerateFile.setProtoSourcePath(protoSourcePath)
                             .setGenerateFolder(generateFolder)
                             .addProtoPackage(protoPackagePath)
                             .generate();
            
            log.info("proto文件生成完成！");
        } catch (Exception e) {
            log.error("生成proto文件时发生错误", e);
        }
    }
    
    /**
     * 快速生成方法
     *
     * @param protoPackagePath proto类所在包路径
     */
    public static void quickGenerate(String protoPackagePath) {
        new ProtoGenerator()
                .setProtoPackagePath(protoPackagePath)
                .generate();
    }
} 