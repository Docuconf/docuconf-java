package dev.docuconf.spring;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bean Validation of a whole object graph, without requiring {@code @Valid} on every nested property. The JSON
 * Schema in the contract constrains nested objects too, so the startup check must as well, or the platform and the
 * app would disagree.
 */
final class GraphValidator {

    /** One problem: where, what, and which constraint. */
    record Problem(String path, String message, String constraint) {
    }

    private GraphValidator() {
    }

    static List<Problem> validate(Validator validator, Object root) {
        Set<String> seenProblems = new LinkedHashSet<>();
        List<Problem> out = new ArrayList<>();
        walk(validator, root, "", out, seenProblems, Collections.newSetFromMap(new IdentityHashMap<>()));
        out.sort((a, b) -> a.path().compareTo(b.path()));
        return out;
    }

    private static void walk(Validator validator, Object value, String path, List<Problem> out, Set<String> seen,
            Set<Object> visited) {
        if (value == null || isLeaf(value.getClass()) || !visited.add(value)) {
            return;
        }
        if (value instanceof Iterable<?> items) {
            int i = 0;
            for (Object item : items) {
                walk(validator, item, path + "[" + i++ + "]", out, seen, visited);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                walk(validator, e.getValue(), path + "[" + e.getKey() + "]", out, seen, visited);
            }
            return;
        }
        if (value.getClass().isArray()) {
            if (value instanceof Object[] arr) {
                for (int i = 0; i < arr.length; i++) {
                    walk(validator, arr[i], path + "[" + i + "]", out, seen, visited);
                }
            }
            return;
        }
        for (ConstraintViolation<Object> cv : validator.validate(value)) {
            String p = join(path, cv.getPropertyPath().toString());
            String annotation = cv.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            if (seen.add(p + "\u0000" + cv.getMessage())) {
                out.add(new Problem(p, cv.getMessage(), annotation));
            }
        }
        for (Map.Entry<String, Object> child : children(value)) {
            walk(validator, child.getValue(), join(path, child.getKey()), out, seen, visited);
        }
    }

    private static List<Map.Entry<String, Object>> children(Object value) {
        List<Map.Entry<String, Object>> out = new ArrayList<>();
        Class<?> type = value.getClass();
        try {
            if (type.isRecord()) {
                for (RecordComponent rc : type.getRecordComponents()) {
                    rc.getAccessor().setAccessible(true);
                    out.add(Map.entry(rc.getName(), nullSafe(rc.getAccessor().invoke(value))));
                }
                return out;
            }
            for (Class<?> c = type; c != null && !isLeaf(c); c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    f.setAccessible(true);
                    out.add(Map.entry(f.getName(), nullSafe(f.get(value))));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not introspectable (a module boundary, say): validate what was reached.
        }
        return out;
    }

    private static Object nullSafe(Object o) {
        return o == null ? NULL : o;
    }

    private static final Object NULL = "";

    private static boolean isLeaf(Class<?> c) {
        if (c.isPrimitive() || c.isEnum() || c == Object.class) {
            return true;
        }
        String n = c.getName();
        boolean jdk = n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jakarta.")
                || n.startsWith("jdk.") || n.startsWith("sun.") || n.startsWith("com.fasterxml.");
        return jdk && !Iterable.class.isAssignableFrom(c) && !Map.class.isAssignableFrom(c);
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return b.startsWith("[") ? a + b : a + "." + b;
    }
}
