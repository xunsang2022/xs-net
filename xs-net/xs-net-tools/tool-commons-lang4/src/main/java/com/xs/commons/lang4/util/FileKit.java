package com.xs.commons.lang4.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 文件工具类
 * 
 * @author  https://github.com/xunsang2022
 */
@Slf4j
public class FileKit {

    /**
     * 创建目录
     *
     * @param dirPath 目录路径
     * @return true 创建成功或目录已存在
     */
    public static boolean mkdir(String dirPath) {
        if (StrKit.isBlank(dirPath)) {
            return false;
        }

        File dir = new File(dirPath);
        if (dir.exists()) {
            return dir.isDirectory();
        }

        try {
            return dir.mkdirs();
        } catch (Exception e) {
            log.error("创建目录失败: {}", dirPath, e);
            return false;
        }
    }

    /**
     * 写入UTF-8字符串到文件
     *
     * @param content  文件内容
     * @param filePath 文件路径
     * @return true 写入成功
     */
    public static boolean writeUtf8String(String content, String filePath) {
        if (content == null || StrKit.isBlank(filePath)) {
            return false;
        }

        try {
            File file = new File(filePath);
            // 确保父目录存在
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            FileUtils.writeStringToFile(file, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            log.error("写入文件失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 读取UTF-8文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容，失败返回null
     */
    public static String readUtf8String(String filePath) {
        if (StrKit.isBlank(filePath)) {
            return null;
        }

        try {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                return null;
            }

            return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            return null;
        }
    }

    /**
     * 检查文件是否存在
     *
     * @param filePath 文件路径
     * @return true 文件存在
     */
    public static boolean exists(String filePath) {
        if (StrKit.isBlank(filePath)) {
            return false;
        }

        File file = new File(filePath);
        return file.exists();
    }

    /**
     * 删除文件或目录
     *
     * @param filePath 文件路径
     * @return true 删除成功
     */
    public static boolean delete(String filePath) {
        if (StrKit.isBlank(filePath)) {
            return false;
        }

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return true;
            }

            if (file.isDirectory()) {
                FileUtils.deleteDirectory(file);
            } else {
                FileUtils.delete(file);
            }
            return true;
        } catch (IOException e) {
            log.error("删除文件失败: {}", filePath, e);
            return false;
        }
    }
} 