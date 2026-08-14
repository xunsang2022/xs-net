package com.proto.generator;

import lombok.extern.slf4j.Slf4j;

/**
 * 命令行入口
 * 
 * @author  https://github.com/xunsang2022
 */
@Slf4j
public class Main {

    private static void printUsage() {
        log.info("Usage: java -jar proto-generator.jar <packagePath> [outputDir]");
        log.info("参数说明:");
        log.info("  packagePath  - 需要扫描的Java包路径，例如: com.example.proto");
        log.info("  outputDir    - 可选，proto文件输出目录，默认为: target/proto");
        log.info("示例:");
        log.info("  java -jar proto-generator.jar com.example.proto");
        log.info("  java -jar proto-generator.jar com.example.proto ./output/proto");
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        // 获取 args 的key


        String packagePath = args.length > 0 ? args[0] : null;
        String outputDir = args.length > 1 ? args[1] : null;

        log.info("Proto Generator 启动...");
        log.info("扫描包路径: {}", packagePath);
        
        try {
            ProtoGenerator generator = new ProtoGenerator()
                    .setProtoPackagePath(packagePath);
            
            if (outputDir != null) {
                generator.setGenerateFolder(outputDir);
                log.info("自定义输出目录: {}", outputDir);
            }
            
            generator.generate();
            
        } catch (Exception e) {
            log.error("生成过程中发生错误", e);
            System.exit(1);
        }
    }
}
