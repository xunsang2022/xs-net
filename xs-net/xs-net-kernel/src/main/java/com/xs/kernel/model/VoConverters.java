package com.xs.kernel.model;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 模型对象到协议记录（VO）的转换注册表。
 *
 * <p>转换实现由协议生成器按 {@code @ProtoRecord} 声明生成，并在生成类的静态初始化中调用
 * {@link #register}；若首次转换时尚未注册，框架会按 {@link #CONVERTER_CLASS_PROPERTY}
 * 指定的类名加载一次转换器持有类来触发注册。</p>
 */
public final class VoConverters {

    /** 转换器持有类的系统属性名，默认取生成插件的默认包路径。 */
    public static final String CONVERTER_CLASS_PROPERTY = "xs.model.converter-class";

    /** 默认转换器持有类，与生成插件的 converterJavaPackage 默认值保持一致。 */
    private static final String DEFAULT_CONVERTER_CLASS = "com.game.message.convert.GeneratedProtoConverters";

    /** 源模型类型到转换函数的映射。 */
    private static final Map<Class<?>, Function<Object, Object>> CONVERTERS = new ConcurrentHashMap<>();

    /** 是否已尝试加载约定的转换器持有类。 */
    private static volatile boolean bootstrapped;

    private VoConverters() {
    }

    /**
     * 注册源模型类型到协议记录的转换。
     *
     * @param sourceType 源模型类型
     * @param targetType 协议记录类型
     * @param converter  转换函数
     */
    public static void register(Class<?> sourceType, Class<?> targetType, Function<Object, Object> converter) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");
        CONVERTERS.put(sourceType, Objects.requireNonNull(converter, "converter"));
    }

    /**
     * 将模型对象转换为协议记录。
     *
     * @param source 模型对象
     * @return 协议记录
     * @throws IllegalStateException 源类型没有注册转换器
     */
    public static Object convert(Object source) {
        Class<?> sourceType = Objects.requireNonNull(source, "source").getClass();
        Function<Object, Object> converter = CONVERTERS.get(sourceType);
        if (converter == null) {
            bootstrap();
            converter = CONVERTERS.get(sourceType);
        }
        if (converter == null) {
            throw new IllegalStateException("No protocol converter registered for " + sourceType.getName()
                    + "; check " + CONVERTER_CLASS_PROPERTY + " or the generated @ProtoRecord converters");
        }
        return converter.apply(source);
    }

    /** 按约定类名加载一次转换器持有类，触发其静态注册。 */
    private static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        synchronized (VoConverters.class) {
            if (bootstrapped) {
                return;
            }
            bootstrapped = true;
            try {
                Class.forName(System.getProperty(CONVERTER_CLASS_PROPERTY, DEFAULT_CONVERTER_CLASS), true,
                        VoConverters.class.getClassLoader());
            } catch (ClassNotFoundException exception) {
                // 未生成转换器时保持未注册状态，由 convert 抛出明确错误。
            }
        }
    }
}
