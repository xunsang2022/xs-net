package com.xs.kernel.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
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

/**
 * xs-net 框架统一的 MyBatis-Plus ActiveRecord 模型基类。
 *
 * <p>除提供 ActiveRecord 能力外，本类还统一处理方法引用式属性更新、
 * 数据库字段推导、Redis Hash 转换和脏字段跟踪。业务 PO 只需继承本类，
 * 即可使用 {@code po.setProperty(ConcretePO::setName, value)} 的链式 API。</p>
 *
 * @param <M> 具体模型类型
 */
@EqualsAndHashCode(callSuper = true, exclude = "dirtyFields")
public abstract class XsModel<M extends XsModel<M>> extends Model<M> {

    /** 按模型类型缓存字段元数据，避免每次 Redis 刷新都重复反射。 */
    private static final Map<Class<?>, List<Property>> PROPERTY_CACHE = new ConcurrentHashMap<>();

    /**
     * 当前模型待同步的数据库字段；该字段只存在于内存，不映射到数据库表。
     */
    @TableField(exist = false)
    private final Set<String> dirtyFields = new LinkedHashSet<>();

    /**
     * 方法引用式 setter 的函数接口。
     *
     * @param <M> 模型类型
     * @param <V> 属性值类型
     */
    @FunctionalInterface
    public interface ModelPropertySetter<M extends XsModel<M>, V> extends Serializable {
        void apply(M model, V value);
    }

    /**
     * 执行 setter 方法引用并记录对应数据库字段。
     *
     * <p>方法引用必须指向当前模型或其父类的实例 setter；方法所属类必须
     * 继承 {@link Model}，否则直接拒绝执行，避免把普通业务方法误当成属性更新。</p>
     *
     * @param setter setter 方法引用
     * @param value 新属性值
     * @param <V> 属性值类型
     * @return 当前模型，支持继续链式设置
     */
    @SuppressWarnings("unchecked")
    public <V> M setProperty(ModelPropertySetter<M, V> setter, V value) {
        Objects.requireNonNull(setter, "setter");
        SerializedLambda lambda = serializedLambda(setter);
        Class<?> declaringClass = declaringClass(lambda);
        if (!Model.class.isAssignableFrom(declaringClass)) {
            throw new IllegalArgumentException("setProperty method owner must extend Model: "
                    + declaringClass.getName());
        }
        if (!declaringClass.isAssignableFrom(getClass())) {
            throw new IllegalArgumentException("setProperty method is not applicable to "
                    + getClass().getName());
        }
        if (lambda.getImplMethodKind() == MethodHandleInfo.REF_invokeStatic) {
            throw new IllegalArgumentException("setProperty requires an instance setter method");
        }
        Property property = propertyForSetter(lambda.getImplMethodName());
        if (property == null) {
            throw new IllegalArgumentException("Unknown model setter: " + lambda.getImplMethodName());
        }
        beforePropertySet(property.column());
        setter.apply((M) this, value);
        dirtyFields.add(property.column());
        return (M) this;
    }

    /**
     * 在属性更新前执行的扩展钩子，例如保存乐观锁 expectedVersion。
     *
     * @param column 即将更新的数据库字段
     */
    protected void beforePropertySet(String column) {
        // 默认无需额外处理。
    }

    /** 标记一个数据库字段为待同步字段。 */
    public void markDirty(String field) {
        property(field);
        dirtyFields.add(field);
    }

    /** 返回按首次修改顺序排列且自动去重的脏字段列表。 */
    public List<String> getDirtyFields() {
        return new ArrayList<>(dirtyFields);
    }

    /** 判断当前模型是否存在待同步字段。 */
    public boolean hasDirtyFields() {
        return !dirtyFields.isEmpty();
    }

    /** 清除全部脏字段。 */
    public void clearDirty() {
        dirtyFields.clear();
    }

    /** 清除指定字段的脏标记。 */
    public void clearDirty(Collection<String> fields) {
        dirtyFields.removeAll(Objects.requireNonNull(fields, "fields"));
    }

    /**
     * 仅清除写入期间没有再次变化的字段，保护异步 Redis/数据库写入期间的新修改。
     */
    public void clearDirtyMatching(Map<String, String> persistedFields) {
        Objects.requireNonNull(persistedFields, "persistedFields").forEach((field, value) -> {
            if (Objects.equals(fieldValue(field), value)) {
                dirtyFields.remove(field);
            }
        });
    }

    /** 将字段转换为兼容旧同步协议的字符串值。 */
    public String fieldValue(String field) {
        return textValue(readField(property(field).field()));
    }

    /** 将模型全部数据库字段转换为字符串 Hash。 */
    public Map<String, String> toHash() {
        return toHash(properties(getClass()).stream().map(Property::column).toList());
    }

    /** 将指定数据库字段转换为字符串 Hash。 */
    public Map<String, String> toHash(Collection<String> fields) {
        Map<String, String> hash = new LinkedHashMap<>();
        for (String field : fields) {
            hash.put(field, fieldValue(field));
        }
        return hash;
    }

    /** 将模型全部数据库字段转换为 JSON Redis Hash 的类型值。 */
    public Map<String, Object> toRedisHash() {
        return toRedisHash(properties(getClass()).stream().map(Property::column).toList());
    }

    /** 将指定数据库字段转换为 JSON Redis Hash 的类型值。 */
    public Map<String, Object> toRedisHash(Collection<String> fields) {
        Map<String, Object> hash = new LinkedHashMap<>();
        for (String field : fields) {
            hash.put(field, redisValue(property(field)));
        }
        return hash;
    }

    /** 按数据库列名获取当前模型的字段元数据。 */
    protected final Property property(String column) {
        return property(getClass(), column);
    }

    /** 判断当前模型是否包含指定数据库列。 */
    protected final boolean hasProperty(String column) {
        return properties(getClass()).stream().anyMatch(property -> property.column().equals(column));
    }

    /** 按模型类型获取字段元数据。 */
    protected static Property property(Class<?> type, String column) {
        return properties(type).stream()
                .filter(property -> property.column().equals(column))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model field: " + column));
    }

    /** 获取指定模型的缓存字段元数据。 */
    protected static List<Property> properties(Class<?> type) {
        return PROPERTY_CACHE.computeIfAbsent(type, XsModel::discoverProperties);
    }

    /** 通过反射读取模型字段。 */
    protected final Object readField(Field field) {
        try {
            field.setAccessible(true);
            return field.get(this);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read model field: " + field.getName(), exception);
        }
    }

    /** 通过反射写入模型字段。 */
    protected final void writeField(Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(this, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to write model field: " + field.getName(), exception);
        }
    }

    /** 将 Redis/数据库读取到的值转换为模型字段类型。 */
    protected static Object convertValue(Object value, Class<?> target) {
        if (value == null || target.isInstance(value)) {
            return value;
        }
        if (target == String.class) return String.valueOf(value);
        if (target == boolean.class || target == Boolean.class) {
            return value instanceof Boolean b ? b : "1".equals(value.toString())
                    || Boolean.parseBoolean(value.toString());
        }
        if (target == int.class || target == Integer.class) return Integer.valueOf(value.toString());
        if (target == long.class || target == Long.class) return Long.valueOf(value.toString());
        if (target == Timestamp.class) return new Timestamp(Long.parseLong(value.toString()));
        return value;
    }

    /** 将字符串 Hash 字段转换成 JSON Redis Hash 所需的类型值。 */
    protected static Map<String, Object> redisValues(Class<?> type, Map<String, String> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach((field, value) -> values.put(field, redisValue(property(type, field), value)));
        return values;
    }

    private Object redisValue(Property property) {
        Object value = readField(property.field());
        if (value == null) {
            return numericType(property.field().getType()) ? 0 : "";
        }
        return value instanceof Timestamp timestamp ? timestamp.getTime() : value;
    }

    private static Object redisValue(Property property, String value) {
        Class<?> type = property.field().getType();
        if (type == Integer.class || type == int.class) return intValue(value, 0);
        if (type == Long.class || type == long.class || type == Timestamp.class) return longValue(value, 0L);
        return value == null ? "" : value;
    }

    private static boolean numericType(Class<?> type) {
        return type == Integer.class || type == int.class
                || type == Long.class || type == long.class || type == Timestamp.class;
    }

    private Property propertyForSetter(String setterMethod) {
        return properties(getClass()).stream()
                .filter(property -> setterNames(property.property()).contains(setterMethod))
                .findFirst()
                .orElse(null);
    }

    private static Set<String> setterNames(String property) {
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return Set.of("set" + suffix, "set" + suffix + "Millis", "set" + suffix + "Value");
    }

    private static SerializedLambda serializedLambda(Serializable setter) {
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

    private static Class<?> declaringClass(SerializedLambda lambda) {
        try {
            return Class.forName(lambda.getImplClass().replace('/', '.'));
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("Unable to resolve setter declaring class: "
                    + lambda.getImplClass(), exception);
        }
    }

    private static List<Property> discoverProperties(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = type; current != null && current != XsModel.class
                && current != Model.class; current = current.getSuperclass()) {
            hierarchy.add(current);
        }
        Collections.reverse(hierarchy);
        List<Property> result = new ArrayList<>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                TableField tableField = field.getAnnotation(TableField.class);
                if (tableField != null && !tableField.exist()) {
                    continue;
                }
                TableId tableId = field.getAnnotation(TableId.class);
                String column = tableId != null && !tableId.value().isBlank()
                        ? tableId.value()
                        : tableField != null && !tableField.value().isBlank()
                        ? tableField.value()
                        : camelToSnake(field.getName());
                result.add(new Property(field, field.getName(), column));
            }
        }
        return List.copyOf(result);
    }

    private static String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static int intValue(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static long longValue(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static String textValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Timestamp timestamp) {
            return String.valueOf(timestamp.getTime());
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        return String.valueOf(value);
    }

    /** 数据库字段元数据。 */
    protected record Property(Field field, String property, String column) {
    }
}
