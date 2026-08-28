package com.game.message.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface Rpc {
    Kind kind() default Kind.REQUEST;

    int number() default 0;

    int requestNumber() default 0;

    int responseNumber() default 0;

    enum Kind {
        MESSAGE,
        INTERNAL,
        REQUEST
    }
}
