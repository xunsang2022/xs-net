package com.xs.kernel.model;

import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 实现 {@link XsModel} 暴露的与持久化无关的操作。
 * <p>
 * 该类为模型对象提供属性读写、脏数据追踪、哈希转换等核心功能，
 * 通过反射机制自动发现模型字段，并支持通过 Lambda 方法引用安全地设置属性值。
 * </p>
 */
public final class XsModelSupport {

    /** 属性缓存：按模型类缓存已发现的属性列表，避免重复反射扫描 */
    private static final Map<Class<?>, List<Property>> PROPERTY_CACHE = new ConcurrentHashMap<>();

    /** 当前支持的模型实例 */
    private final Object model;
    /** 字段映射器：负责判断字段是否持久化以及字段名到列名的转换 */
    private final FieldMapper fieldMapper;
    /** 属性设置前的回调函数：用于在属性被修改前执行前置逻辑（如通知、校验等） */
    private final Consumer<String> beforePropertySet;
    /** 脏字段集合：记录自上次清除以来被修改过的字段列名，保持插入顺序 */
    private final Set<String> dirtyFields = new LinkedHashSet<>();

    /**
     * 构造 XsModelSupport 实例。
     *
     * @param model           要支持的模型对象，不能为空
     * @param fieldMapper     字段映射器，用于判断字段是否持久化及列名转换，不能为空
     * @param beforePropertySet 属性设置前的回调函数，不能为空
     * @throws NullPointerException 如果任一参数为空
     */
    public XsModelSupport(Object model, FieldMapper fieldMapper, Consumer<String> beforePropertySet) {
        this.model = Objects.requireNonNull(model, "model");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper");
        this.beforePropertySet = Objects.requireNonNull(beforePropertySet, "beforePropertySet");
    }

    /**
     * 通过 Lambda 方法引用安全地设置模型属性值。
     * <p>
     * 该方法会验证 setter 方法引用的合法性，自动追踪脏字段，
     * 并在设置前调用前置回调函数。
     * </p>
     *
     * @param <M>    模型类型，必须实现 XsModel 接口
     * @param <V>    属性值类型
     * @param target 目标模型实例，必须是当前 XsModelSupport 绑定的模型
     * @param setter 模型属性的 setter 方法引用（如 Model::setName）
     * @param value  要设置的属性值
     * @return 返回目标模型实例，支持链式调用
     * @throws NullPointerException     如果 setter 为空
     * @throws IllegalArgumentException 如果 target 不是当前模型实例、setter 方法不合法或无法识别
     */
    public <M extends XsModel<M>, V> M setProperty(
            M target, XsModel.ModelPropertySetter<M, V> setter, V value) {
        Objects.requireNonNull(setter, "setter");
        // 验证 target 必须是当前绑定的模型实例
        if (target != model) {
            throw new IllegalArgumentException("XsModelSupport belongs to a different model instance");
        }
        // 解析 Lambda 方法引用，获取底层方法信息
        SerializedLambda lambda = serializedLambda(setter);
        Class<?> declaringClass = declaringClass(lambda);
        // 验证 setter 方法的声明类必须实现 XsModel 接口
        if (!XsModel.class.isAssignableFrom(declaringClass)) {
            throw new IllegalArgumentException("setProperty method owner must implement XsModel: "
                    + declaringClass.getName());
        }
        // 验证 setter 方法适用于当前模型类型
        if (!declaringClass.isAssignableFrom(model.getClass())) {
            throw new IllegalArgumentException("setProperty method is not applicable to "
                    + model.getClass().getName());
        }
        // 验证必须是实例方法而非静态方法
        if (lambda.getImplMethodKind() == MethodHandleInfo.REF_invokeStatic) {
            throw new IllegalArgumentException("setProperty requires an instance setter method");
        }
        // 根据 setter 方法名查找对应的属性定义
        Property property = propertyForSetter(lambda.getImplMethodName());
        if (property == null) {
            throw new IllegalArgumentException("Unknown model setter: " + lambda.getImplMethodName());
        }
        // 执行前置回调，通知属性即将被修改
        beforePropertySet.accept(property.column());
        // 通过 Lambda 设置属性值
        setter.apply(target, value);
        // 将字段标记为脏（已修改）
        dirtyFields.add(property.column());
        return target;
    }

    /**
     * 获取所有脏字段的列名列表。
     *
     * @return 脏字段列名的新列表，保持插入顺序
     */
    public List<String> getDirtyFields() {
        return new ArrayList<>(dirtyFields);
    }

    /**
     * 判断是否存在脏字段。
     *
     * @return 如果有脏字段返回 true，否则返回 false
     */
    public boolean hasDirtyFields() {
        return !dirtyFields.isEmpty();
    }

    /**
     * 根据持久化后的值清除脏字段。
     * <p>
     * 遍历给定的持久化字段映射，如果模型中对应字段的当前值与持久化值相同，
     * 则从脏字段集合中移除该字段（说明该字段实际未被修改）。
     * </p>
     *
     * @param persistedFields 持久化后的字段列名到值的映射
     * @throws NullPointerException 如果 persistedFields 为空
     */
    public void clearDirtyMatching(Map<String, String> persistedFields) {
        Objects.requireNonNull(persistedFields, "persistedFields").forEach((field, value) -> {
            if (Objects.equals(fieldValue(field), value)) {
                dirtyFields.remove(field);
            }
        });
    }

    /**
     * 获取指定字段的文本值。
     *
     * @param field 字段的列名
     * @return 字段的文本表示形式（null 返回空字符串，Timestamp 返回毫秒数，Boolean 返回 "1"/"0"）
     * @throws IllegalArgumentException 如果字段名不存在
     */
    private String fieldValue(String field) {
        return textValue(readField(property(field).field()));
    }

    /**
     * 将所有持久化字段转换为字符串哈希映射。
     *
     * @return 字段列名到字符串值的有序映射
     */
    public Map<String, String> toHash() {
        return toHash(properties().stream().map(Property::column).toList());
    }

    /**
     * 把模型对象转换为 {@code @ProtoRecord} 声明的协议记录。
     *
     * @param model 模型对象
     * @return 协议记录
     */
    public Object toVo(Object model) {
        return VoConverters.convert(model);
    }

    /**
     * 将指定字段转换为字符串哈希映射。
     *
     * @param fields 要转换的字段列名集合
     * @return 字段列名到字符串值的有序映射
     */
    public Map<String, String> toHash(Collection<String> fields) {
        Map<String, String> hash = new LinkedHashMap<>();
        for (String field : fields) {
            hash.put(field, fieldValue(field));
        }
        return hash;
    }

    /**
     * 将所有持久化字段转换为对象值映射。
     * <p>
     * 与 {@link #toHash()} 不同，此方法保留原始类型（如 Integer、Long、Timestamp 转为毫秒数），
     * null 值会被替换为数值类型的 0 或字符串类型的空字符串。
     * </p>
     *
     * @return 字段列名到对象值的有序映射
     */
    public Map<String, Object> toValueMap() {
        return toValueMap(properties().stream().map(Property::column).toList());
    }

    /**
     * 将指定字段转换为对象值映射。
     *
     * @param fields 要转换的字段列名集合
     * @return 字段列名到对象值的有序映射
     */
    public Map<String, Object> toValueMap(Collection<String> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : fields) {
            Property property = property(field);
            Object value = readField(property.field());
            if (value == null) {
                value = numericType(property.field().getType()) ? 0 : "";
            } else if (value instanceof Timestamp timestamp) {
                value = timestamp.getTime();
            }
            values.put(field, value);
        }
        return values;
    }

    /**
     * 从哈希映射中恢复模型字段值。
     * <p>
     * 遍历给定的映射，将每个键值对写入对应的模型字段。
     * 会跳过 null 值和不存在的字段，并进行必要的类型转换。
     * </p>
     *
     * @param fields 字段列名到值的映射（键会被转为字符串作为列名）
     */
    public void fromHash(Map<?, ?> fields) {
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (entry.getValue() == null || !hasProperty(field)) {
                continue;
            }
            Property property = property(field);
            writeField(property.field(), convertValue(entry.getValue(), property.field().getType()));
        }
    }

    /**
     * 获取模型的所有持久化属性列表（带缓存）。
     *
     * @return 属性列表（不可变）
     */
    private List<Property> properties() {
        return PROPERTY_CACHE.computeIfAbsent(model.getClass(), ignored -> discoverProperties());
    }

    /**
     * 根据列名查找对应的属性定义。
     *
     * @param column 字段的列名
     * @return 对应的属性定义
     * @throws IllegalArgumentException 如果列名不存在
     */
    private Property property(String column) {
        return properties().stream()
                .filter(property -> property.column().equals(column))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model field: " + column));
    }

    /**
     * 判断指定列名的字段是否存在。
     *
     * @param column 字段的列名
     * @return 如果存在返回 true，否则返回 false
     */
    private boolean hasProperty(String column) {
        return properties().stream().anyMatch(property -> property.column().equals(column));
    }

    /**
     * 根据 setter 方法名查找对应的属性定义。
     *
     * @param setterMethod setter 方法名（如 "setName"）
     * @return 对应的属性定义，如果找不到返回 null
     */
    private Property propertyForSetter(String setterMethod) {
        return properties().stream()
                .filter(property -> setterNames(property.property()).contains(setterMethod))
                .findFirst()
                .orElse(null);
    }

    /**
     * 通过反射读取模型字段的值。
     *
     * @param field 要读取的字段
     * @return 字段的值
     * @throws IllegalStateException 如果无法访问字段
     */
    private Object readField(Field field) {
        try {
            field.setAccessible(true);
            return field.get(model);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read model field: " + field.getName(), exception);
        }
    }

    /**
     * 通过反射写入模型字段的值。
     *
     * @param field 要写入的字段
     * @param value 要设置的值
     * @throws IllegalStateException 如果无法访问字段
     */
    private void writeField(Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(model, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to write model field: " + field.getName(), exception);
        }
    }

    /**
     * 通过反射扫描模型类及其父类，发现所有持久化字段。
     * <p>
     * 会遍历整个类继承层次（Object 除外），过滤掉静态字段、合成字段
     * 以及 FieldMapper 判定为非持久化的字段。
     * </p>
     *
     * @return 发现的属性列表（不可变）
     */
    private List<Property> discoverProperties() {
        // 构建类继承层次（从 Object 到当前类的父类）
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = model.getClass(); current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current == XsModelSupport.class) {
                continue;
            }
            hierarchy.add(current);
        }
        // 反转为从父类到子类的顺序
        Collections.reverse(hierarchy);
        List<Property> result = new ArrayList<>();
        // 遍历每个类，扫描其声明的字段
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                // 跳过静态字段、合成字段、非持久化字段
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                        || !fieldMapper.isPersistentField(field)) {
                    continue;
                }
                result.add(new Property(field, field.getName(), fieldMapper.columnName(field)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 解析 Lambda 方法引用，获取底层方法信息。
     *
     * @param setter 模型属性的 setter 方法引用
     * @return 序列化后的 Lambda 表达式信息
     * @throws IllegalArgumentException 如果无法解析方法引用
     */
    private static SerializedLambda serializedLambda(XsModel.ModelPropertySetter<?, ?> setter) {
        try {
            Method writeReplace = setter.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replacement = writeReplace.invoke(setter);
            if (replacement instanceof SerializedLambda lambda) {
                return lambda;
            }
            throw new IllegalArgumentException("Unsupported setter method reference");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Unable to inspect setter method reference", exception);
        }
    }

    /**
     * 从序列化 Lambda 中解析出声明该方法的类。
     *
     * @param lambda 序列化的 Lambda 表达式
     * @return 声明方法的类
     * @throws IllegalArgumentException 如果无法解析类名
     */
    private static Class<?> declaringClass(SerializedLambda lambda) {
        try {
            return Class.forName(lambda.getImplClass().replace('/', '.'));
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("Unable to resolve setter declaring class: "
                    + lambda.getImplClass(), exception);
        }
    }

    /**
     * 根据属性名生成可能的 setter 方法名集合。
     * <p>
     * 支持三种命名约定：setXxx、setXxxMillis（用于时间戳）、setXxxValue。
     * </p>
     *
     * @param property 属性名（如 "name"）
     * @return 可能的 setter 方法名集合（如 ["setName", "setNameMillis", "setNameValue"]）
     */
    private static Set<String> setterNames(String property) {
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return Set.of("set" + suffix, "set" + suffix + "Millis", "set" + suffix + "Value");
    }

    /**
     * 将值转换为目标类型。
     * <p>
     * 支持的类型转换包括：字符串、布尔值（"1" 或 "true" 为真）、整数、长整数、时间戳。
     * </p>
     *
     * @param value  要转换的值
     * @param target 目标类型
     * @return 转换后的值，如果无法转换则返回原值
     */
    private static Object convertValue(Object value, Class<?> target) {
        if (value == null || target.isInstance(value)) return value;
        if (target == String.class) return String.valueOf(value);
        if (target == boolean.class || target == Boolean.class) {
            return value instanceof Boolean bool ? bool : "1".equals(value.toString())
                    || Boolean.parseBoolean(value.toString());
        }
        if (target == int.class || target == Integer.class) return Integer.valueOf(value.toString());
        if (target == long.class || target == Long.class) return Long.valueOf(value.toString());
        if (target == Timestamp.class) return new Timestamp(Long.parseLong(value.toString()));
        return value;
    }

    /**
     * 判断指定类型是否为数值类型（包括时间戳）。
     *
     * @param type 要检查的类型
     * @return 如果是数值类型返回 true，否则返回 false
     */
    private static boolean numericType(Class<?> type) {
        return type == Integer.class || type == int.class || type == Long.class
                || type == long.class || type == Timestamp.class;
    }

    /**
     * 将对象转换为文本表示形式。
     * <p>
     * 转换规则：null 转为空字符串，Timestamp 转为毫秒数，Boolean 转为 "1"/"0"，其他类型调用 toString()。
     * </p>
     *
     * @param value 要转换的对象
     * @return 文本表示形式
     */
    private static String textValue(Object value) {
        return switch (value) {
            case null -> "";
            case Timestamp timestamp -> String.valueOf(timestamp.getTime());
            case Boolean bool -> bool ? "1" : "0";
            default -> String.valueOf(value);
        };
    }

    /**
     * 字段映射器接口：负责判断字段是否持久化以及字段名到列名的转换。
     */
    public interface FieldMapper {
        /**
         * 判断指定字段是否为持久化字段。
         *
         * @param field 要检查的字段
         * @return 如果是持久化字段返回 true，否则返回 false
         */
        boolean isPersistentField(Field field);

        /**
         * 将字段名转换为数据库列名。
         *
         * @param field 要转换的字段
         * @return 对应的列名
         */
        String columnName(Field field);
    }

    /**
     * 属性定义记录：封装字段的反射信息、属性名和列名。
     *
     * @param field    字段的反射对象
     * @param property 属性名（即字段名）
     * @param column   数据库列名
     */
    private record Property(Field field, String property, String column) {
    }
}
