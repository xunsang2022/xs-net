package com.proto.generator.annotation;

import java.lang.annotation.*;

/**
 * Proto文件合并归类注解
 *
 * @author <a href="https://github.com/xunsang2022">xunsang2022</a>
 * 
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ProtoFileMerge {
    
    /** 原生 .proto 的文件名 */
    String fileName();

    /** 原生 .proto 的包名 package */
    String filePackage();
} 