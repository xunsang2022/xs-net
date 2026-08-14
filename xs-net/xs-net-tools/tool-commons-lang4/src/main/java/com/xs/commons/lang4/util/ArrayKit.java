package com.xs.commons.lang4.util;

import java.util.Objects;

/**
 * 数组工具类
 * 
 * @author  https://github.com/xunsang2022
 */
public class ArrayKit {

    /**
     * 使用分隔符连接数组元素
     *
     * @param array     数组
     * @param separator 分隔符
     * @return 连接后的字符串
     */
    public static String join(String[] array, String separator) {
        if (array == null || array.length == 0) {
            return "";
        }
        
        if (array.length == 1) {
            return array[0];
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(array[i]);
        }
        
        return sb.toString();
    }

    /**
     * 检查数组是否为空
     *
     * @param array 数组
     * @return true 如果数组为null或长度为0
     */
    public static boolean isEmpty(Object[] array) {
        return array == null || array.length == 0;
    }

    /**
     * 检查数组是否不为空
     *
     * @param array 数组
     * @return true 如果数组不为null且长度大于0
     */
    public static boolean isNotEmpty(Object[] array) {
        return !isEmpty(array);
    }
} 