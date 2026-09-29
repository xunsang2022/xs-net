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

/** Implements the persistence-agnostic operations exposed by {@link XsModel}. */
public final class XsModelSupport {

    private static final Map<Class<?>, List<Property>> PROPERTY_CACHE = new ConcurrentHashMap<>();

    private final Object model;
    private final FieldMapper fieldMapper;
    private final Consumer<String> beforePropertySet;
    private final Set<String> dirtyFields = new LinkedHashSet<>();

    public XsModelSupport(Object model, FieldMapper fieldMapper, Consumer<String> beforePropertySet) {
        this.model = Objects.requireNonNull(model, "model");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper");
        this.beforePropertySet = Objects.requireNonNull(beforePropertySet, "beforePropertySet");
    }

    public <M extends XsModel<M>, V> M setProperty(
            M target, XsModel.ModelPropertySetter<M, V> setter, V value) {
        Objects.requireNonNull(setter, "setter");
        if (target != model) {
            throw new IllegalArgumentException("XsModelSupport belongs to a different model instance");
        }
        SerializedLambda lambda = serializedLambda(setter);
        Class<?> declaringClass = declaringClass(lambda);
        if (!XsModel.class.isAssignableFrom(declaringClass)) {
            throw new IllegalArgumentException("setProperty method owner must implement XsModel: "
                    + declaringClass.getName());
        }
        if (!declaringClass.isAssignableFrom(model.getClass())) {
            throw new IllegalArgumentException("setProperty method is not applicable to "
                    + model.getClass().getName());
        }
        if (lambda.getImplMethodKind() == MethodHandleInfo.REF_invokeStatic) {
            throw new IllegalArgumentException("setProperty requires an instance setter method");
        }
        Property property = propertyForSetter(lambda.getImplMethodName());
        if (property == null) {
            throw new IllegalArgumentException("Unknown model setter: " + lambda.getImplMethodName());
        }
        beforePropertySet.accept(property.column());
        setter.apply(target, value);
        dirtyFields.add(property.column());
        return target;
    }

    public void markDirty(String field) {
        property(field);
        dirtyFields.add(field);
    }

    public List<String> getDirtyFields() {
        return new ArrayList<>(dirtyFields);
    }

    public boolean hasDirtyFields() {
        return !dirtyFields.isEmpty();
    }

    public void clearDirty() {
        dirtyFields.clear();
    }

    public void clearDirty(Collection<String> fields) {
        dirtyFields.removeAll(Objects.requireNonNull(fields, "fields"));
    }

    public void clearDirtyMatching(Map<String, String> persistedFields) {
        Objects.requireNonNull(persistedFields, "persistedFields").forEach((field, value) -> {
            if (Objects.equals(fieldValue(field), value)) {
                dirtyFields.remove(field);
            }
        });
    }

    public String fieldValue(String field) {
        return textValue(readField(property(field).field()));
    }

    public Map<String, String> toHash() {
        return toHash(properties().stream().map(Property::column).toList());
    }

    public Map<String, String> toHash(Collection<String> fields) {
        Map<String, String> hash = new LinkedHashMap<>();
        for (String field : fields) {
            hash.put(field, fieldValue(field));
        }
        return hash;
    }

    public Map<String, Object> toValueMap() {
        return toValueMap(properties().stream().map(Property::column).toList());
    }

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

    private List<Property> properties() {
        return PROPERTY_CACHE.computeIfAbsent(model.getClass(), ignored -> discoverProperties());
    }

    private Property property(String column) {
        return properties().stream()
                .filter(property -> property.column().equals(column))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model field: " + column));
    }

    private boolean hasProperty(String column) {
        return properties().stream().anyMatch(property -> property.column().equals(column));
    }

    private Property propertyForSetter(String setterMethod) {
        return properties().stream()
                .filter(property -> setterNames(property.property()).contains(setterMethod))
                .findFirst()
                .orElse(null);
    }

    private Object readField(Field field) {
        try {
            field.setAccessible(true);
            return field.get(model);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read model field: " + field.getName(), exception);
        }
    }

    private void writeField(Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(model, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to write model field: " + field.getName(), exception);
        }
    }

    private List<Property> discoverProperties() {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = model.getClass(); current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current == XsModelSupport.class) {
                continue;
            }
            hierarchy.add(current);
        }
        Collections.reverse(hierarchy);
        List<Property> result = new ArrayList<>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                        || !fieldMapper.isPersistentField(field)) {
                    continue;
                }
                result.add(new Property(field, field.getName(), fieldMapper.columnName(field)));
            }
        }
        return List.copyOf(result);
    }

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

    private static Class<?> declaringClass(SerializedLambda lambda) {
        try {
            return Class.forName(lambda.getImplClass().replace('/', '.'));
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("Unable to resolve setter declaring class: "
                    + lambda.getImplClass(), exception);
        }
    }

    private static Set<String> setterNames(String property) {
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return Set.of("set" + suffix, "set" + suffix + "Millis", "set" + suffix + "Value");
    }

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

    private static boolean numericType(Class<?> type) {
        return type == Integer.class || type == int.class || type == Long.class
                || type == long.class || type == Timestamp.class;
    }

    private static String textValue(Object value) {
        return switch (value) {
            case null -> "";
            case Timestamp timestamp -> String.valueOf(timestamp.getTime());
            case Boolean bool -> bool ? "1" : "0";
            default -> String.valueOf(value);
        };
    }

    public interface FieldMapper {
        boolean isPersistentField(Field field);

        String columnName(Field field);
    }

    private record Property(Field field, String property, String column) {
    }
}
