package com.xs.core.spring;

import lombok.NonNull;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;

@Configuration
public class SpringUtil implements ApplicationListener<@NonNull ContextRefreshedEvent> {
    private static ApplicationContext context = null;

    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        SpringUtil.context = event.getApplicationContext();
    }

    public static <T> T getBean(Class<T> clazz) {
        return context.getBean(clazz);
    }
}
