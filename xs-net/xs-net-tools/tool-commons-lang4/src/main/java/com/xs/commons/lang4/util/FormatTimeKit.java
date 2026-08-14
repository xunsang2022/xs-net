package com.xs.commons.lang4.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间格式化工具类
 * 
 * @author  https://github.com/xunsang2022
 */
public class FormatTimeKit {
    
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 格式化当前时间
     *
     * @return 格式化后的时间字符串
     */
    public static String format() {
        return LocalDateTime.now().format(DEFAULT_FORMATTER);
    }
    
    /**
     * 格式化指定时间
     *
     * @param dateTime 时间
     * @return 格式化后的时间字符串
     */
    public static String format(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DEFAULT_FORMATTER);
    }
    
    /**
     * 使用指定格式格式化当前时间
     *
     * @param pattern 时间格式
     * @return 格式化后的时间字符串
     */
    public static String format(String pattern) {
        if (StrKit.isBlank(pattern)) {
            return format();
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            return LocalDateTime.now().format(formatter);
        } catch (Exception e) {
            return format();
        }
    }
    
    /**
     * 使用指定格式格式化指定时间
     *
     * @param dateTime 时间
     * @param pattern  时间格式
     * @return 格式化后的时间字符串
     */
    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return "";
        }
        
        if (StrKit.isBlank(pattern)) {
            return format(dateTime);
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            return dateTime.format(formatter);
        } catch (Exception e) {
            return format(dateTime);
        }
    }
} 