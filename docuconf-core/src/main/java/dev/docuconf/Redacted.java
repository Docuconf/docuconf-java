package dev.docuconf;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.StringJoiner;

/**
 * {@code toString()} for configuration classes that hold {@link Secret} values: every property is printed as a
 * record or a Lombok {@code @ToString} would print it, except secrets, which print as {@code [redacted]}.
 *
 * <p>A record's generated {@code toString()} prints every component, secrets included, so the annotation processor
 * requires records (and Lombok {@code @Data}/{@code @ToString}/{@code @Value} classes) holding a secret to override
 * it:
 *
 * <pre>{@code
 * public record BillingProperties(@Secret URI databaseUrl, int port) {
 *     @Override
 *     public String toString() {
 *         return Redacted.toString(this);   // BillingProperties[databaseUrl=[redacted], port=8080]
 *     }
 * }
 * }</pre>
 */
public final class Redacted {

    /** What a secret value prints as. */
    public static final String MARK = "[redacted]";

    private Redacted() {
    }

    /**
     * Prints an object's properties with {@link Secret} ones redacted: {@code Name[a=1, secret=[redacted]]} for a
     * record, {@code Name(a=1, secret=[redacted])} for a class.
     *
     * @param object a record or a class with fields
     * @return the text
     */
    public static String toString(Object object) {
        if (object == null) {
            return "null";
        }
        Class<?> type = object.getClass();
        boolean record = type.isRecord();
        StringJoiner out = new StringJoiner(", ", type.getSimpleName() + (record ? "[" : "("), record ? "]" : ")");
        if (record) {
            for (RecordComponent rc : type.getRecordComponents()) {
                boolean secret = rc.isAnnotationPresent(Secret.class) || field(type, rc.getName()) != null
                        && field(type, rc.getName()).isAnnotationPresent(Secret.class);
                out.add(rc.getName() + "=" + (secret ? MARK : value(rc.getAccessor(), object)));
            }
        } else {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    out.add(f.getName() + "=" + (f.isAnnotationPresent(Secret.class) ? MARK : value(f, object)));
                }
            }
        }
        return out.toString();
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static String value(AccessibleObject member, Object object) {
        try {
            member.setAccessible(true);
            Object v = member instanceof Field f ? f.get(object)
                    : ((java.lang.reflect.Method) member).invoke(object);
            return String.valueOf(v);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }
}
