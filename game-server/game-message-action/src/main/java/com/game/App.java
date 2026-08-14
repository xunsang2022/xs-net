package com.game;

import com.proto.generator.ProtoGenerator;
import lombok.extern.slf4j.Slf4j;

/**
 * Hello world!
 */
@Slf4j
public class App {
    public static void main(String[] args) {
//        if (args.length == 0) {
//            printUsage();
//            return;
//        }

//        String packagePath = args[0];
//        String outputDir = args.length > 1 ? args[1] : null;
//        String packagePath = "E:\\project\\game\\game-server\\game-message-action\\src\\main\\java\\com\\game\\message\\action";
//        String outputDir = null;
//
//        log.info("Proto Generator 启动...");
//        log.info("扫描包路径: {}", packagePath);
//
//        try {
//            ProtoGenerator generator = new ProtoGenerator()
//                    .setProtoPackagePath(packagePath);
//
//            if (outputDir != null) {
//                generator.setGenerateFolder(outputDir);
//                log.info("自定义输出目录: {}", outputDir);
//            }
//
//            generator.generate();
//
//        } catch (Exception e) {
//            log.error("生成过程中发生错误", e);
//            XSSystem.exit(1);
//        }

        ProtoGenerator.quickGenerate("com.game.message.action");

//        String packagePath = "E:\\project\\game\\game-server\\game-message-action\\src\\main\\java\\com\\game\\message\\action";
//        String outputDir = "E:\\project\\game\\game-server\\game-message-action\\src\\main\\resources\\proto";
//
//        log.info("Proto Generator 启动...");
//        log.info("扫描包路径: {}", packagePath);
//
//        try {
//            ProtoGenerator generator = new ProtoGenerator()
//                    .setProtoPackagePath(packagePath);
//
//            if (outputDir != null) {
//                generator.setGenerateFolder(outputDir);
//                log.info("自定义输出目录: {}", outputDir);
//            }
//
//            generator.generate();
//
//        } catch (Exception e) {
//            log.error("生成过程中发生错误", e);
//            XSSystem.exit(1);
//        }
    }
}
