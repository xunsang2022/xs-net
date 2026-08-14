package com.xs.commons.lang4.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字符串工具类
 * 
 * @author  https://github.com/xunsang2022
 */
public class StrKit {

    /**
     * 检查字符串是否为空白
     *
     * @param str 字符串
     * @return true 如果字符串为null、空字符串或只包含空白字符
     */
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * 检查字符串是否不为空白
     *
     * @param str 字符串
     * @return true 如果字符串不为null且不为空白
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    /**
     * 使用Map参数格式化字符串模板
     * 模板中的占位符格式为：{key}
     *
     * @param template 字符串模板
     * @param params   参数Map
     * @return 格式化后的字符串
     */
    public static String format(String template, Map<String, String> params) {
        if (isBlank(template) || params == null || params.isEmpty()) {
            return template;
        }

        String result = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{" + key + "}", value);
        }
        
        return result;
    }

    /**
     * 使用参数数组格式化字符串模板
     * 模板中的占位符格式为：{}
     *
     * @param template 字符串模板
     * @param args     参数数组
     * @return 格式化后的字符串
     */
    public static String format(String template, Object... args) {
        if (isBlank(template) || args == null || args.length == 0) {
            return template;
        }

        String result = template;
        Pattern pattern = Pattern.compile("\\{\\}");
        
        for (Object arg : args) {
            String value = arg != null ? arg.toString() : "";
            // 使用Matcher.quoteReplacement来安全地处理包含特殊字符的替换字符串
            result = pattern.matcher(result).replaceFirst(Matcher.quoteReplacement(value));
        }
        
        return result;
    }

    /**
     * 获取默认值
     *
     * @param str          原字符串
     * @param defaultValue 默认值
     * @return 如果原字符串为空白则返回默认值，否则返回原字符串
     */
    public static String defaultIfBlank(String str, String defaultValue) {
        return isBlank(str) ? defaultValue : str;
    }

    /**
     * 首字母小写
     *
     * @param str 字符串
     * @return 首字母小写的字符串
     */
    public static String lowerFirst(String str) {
        if (isBlank(str)) {
            return str;
        }
        
        if (str.length() == 1) {
            return str.toLowerCase();
        }
        
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    /**
     * 首字母大写
     *
     * @param str 字符串
     * @return 首字母大写的字符串
     */
    public static String upperFirst(String str) {
        if (isBlank(str)) {
            return str;
        }
        
        if (str.length() == 1) {
            return str.toUpperCase();
        }
        
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
} 