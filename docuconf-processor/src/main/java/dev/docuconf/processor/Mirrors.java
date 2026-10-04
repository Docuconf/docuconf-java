package dev.docuconf.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/** Reads annotations by name, so the processor needs neither Spring nor Bean Validation on its classpath. */
final class Mirrors {

    private Mirrors() {
    }

    /** The annotations on a set of elements and types, by qualified name; the first occurrence wins. */
    static Map<String, AnnotationMirror> collect(List<? extends Object> sources) {
        Map<String, AnnotationMirror> all = new LinkedHashMap<>();
        for (Object source : sources) {
            if (source == null) {
                continue;
            }
            List<? extends AnnotationMirror> mirrors = source instanceof Element e ? e.getAnnotationMirrors()
                    : ((TypeMirror) source).getAnnotationMirrors();
            for (AnnotationMirror m : mirrors) {
                String name = ((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().toString();
                all.putIfAbsent(name, m);
                // Repeated annotations arrive in their container (@Pattern.List and so on).
                if (name.endsWith(".List")) {
                    Object value = rawValue(m, "value");
                    if (value instanceof List<?> l) {
                        if (l.size() > 1) {
                            all.put(name.substring(0, name.length() - 5) + "#multiple", m);
                        }
                        for (Object o : l) {
                            AnnotationMirror inner = (AnnotationMirror) ((AnnotationValue) o).getValue();
                            String innerName = ((TypeElement) inner.getAnnotationType().asElement()).getQualifiedName()
                                    .toString();
                            all.putIfAbsent(innerName, inner);
                        }
                    }
                }
            }
        }
        return all;
    }

    /** An explicitly given annotation value, or {@code null}. */
    static Object rawValue(AnnotationMirror m, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : m.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(name)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }

    /** An annotation value, falling back to the annotation's default. */
    static Object value(Elements elements, AnnotationMirror m, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                : elements.getElementValuesWithDefaults(m).entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(name)) {
                return unwrap(e.getValue().getValue());
            }
        }
        return null;
    }

    static String string(Elements elements, AnnotationMirror m, String name) {
        Object v = value(elements, m, name);
        return v == null ? null : v.toString();
    }

    static List<String> strings(Elements elements, AnnotationMirror m, String name) {
        Object v = value(elements, m, name);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                out.add(o.toString());
            }
        } else if (v != null) {
            out.add(v.toString());
        }
        return out;
    }

    static long number(Elements elements, AnnotationMirror m, String name) {
        Object v = value(elements, m, name);
        return v == null ? 0 : ((Number) v).longValue();
    }

    static boolean bool(Elements elements, AnnotationMirror m, String name) {
        return Boolean.TRUE.equals(value(elements, m, name));
    }

    private static Object unwrap(Object v) {
        if (v instanceof VariableElement ve) {
            return ve.getSimpleName().toString(); // an enum constant
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(unwrap(((AnnotationValue) o).getValue()));
            }
            return out;
        }
        return v;
    }
}
