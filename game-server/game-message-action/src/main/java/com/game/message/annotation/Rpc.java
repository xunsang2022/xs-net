package com.game.message.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface Rpc {
    Kind kind() default Kind.MESSAGE;

    int number() default 0;

    enum Kind {
        MESSAGE,
        INTERNAL,
        /** 标识 Actor 模型数据，可编解码传输。 */
        ACTOR_MODEL
    }
}
