package com.game.message.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明该类型可通过 classType 指定的协议记录转换为协议数据。 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ProtoRecord {
    /** 协议记录的全限定类名，避免编译期依赖该类。 */
    String classType();

    /** 协议记录的 RPC 编号。 */
    int number();
}
