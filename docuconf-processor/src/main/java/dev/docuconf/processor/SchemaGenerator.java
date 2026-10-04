package dev.docuconf.processor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * JSON Schema (draft 2020-12 keywords) for the type a config file or {@code json} variable is deserialized into
 * with Jackson: records by component, classes by settable property, {@code @JsonProperty} names, Bean Validation
 * constraints as keywords, and {@code additionalProperties: false} because docuconf's reader rejects unknown
 * properties.
 */
final class SchemaGenerator {

    private final Elements elements;
    private final Types types;
    private final List<String> errors = new ArrayList<>();

    SchemaGenerator(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    List<String> errors() {
        return errors;
    }

    Map<String, Object> schema(TypeMirror type) {
        return schema(type, new HashSet<>());
    }

    private Map<String, Object> schema(TypeMirror type, Set<String> visiting) {
        Map<String, Object> s = new LinkedHashMap<>();
        switch (type.getKind()) {
            case BOOLEAN -> s.put("type", "boolean");
            case INT, LONG, SHORT, BYTE -> s.put("type", "integer");
            case FLOAT, DOUBLE -> s.put("type", "number");
            case CHAR -> {
                s.put("type", "string");
                s.put("minLength", 1L);
                s.put("maxLength", 1L);
            }
            case ARRAY -> {
                s.put("type", "array");
                s.put("items", schema(((ArrayType) type).getComponentType(), visiting));
            }
            case DECLARED -> declared((DeclaredType) type, s, visiting);
            default -> {
            }
        }
        return s;
    }

    private void declared(DeclaredType type, Map<String, Object> s, Set<String> visiting) {
        TypeElement te = (TypeElement) type.asElement();
        String name = te.getQualifiedName().toString();
        switch (name) {
            case "java.lang.String", "java.lang.CharSequence", "java.nio.file.Path", "java.io.File",
                    "java.util.Locale", "java.nio.charset.Charset", "java.time.ZoneId", "java.util.UUID" -> {
                s.put("type", "string");
                return;
            }
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.math.BigInteger" -> {
                s.put("type", "integer");
                return;
            }
            case "java.lang.Double", "java.lang.Float", "java.math.BigDecimal", "java.lang.Number" -> {
                s.put("type", "number");
                return;
            }
            case "java.lang.Boolean" -> {
                s.put("type", "boolean");
                return;
            }
            case "java.time.Duration" -> {
                // Jackson's JavaTimeModule reads ISO-8601 ("PT30S") or a number of seconds.
                s.put("type", List.of("string", "number"));
                return;
            }
            case "java.net.URI", "java.net.URL" -> {
                s.put("type", "string");
                s.put("format", "uri");
                return;
            }
            case "java.lang.Object", "com.fasterxml.jackson.databind.JsonNode" -> {
                return;
            }
            case "java.util.Optional" -> {
                if (!type.getTypeArguments().isEmpty()) {
                    s.putAll(schema(type.getTypeArguments().get(0), visiting));
                }
                return;
            }
            default -> {
            }
        }
        if (te.getKind() == ElementKind.ENUM) {
            s.put("type", "string");
            List<Object> values = new ArrayList<>();
            for (Element e : te.getEnclosedElements()) {
                if (e.getKind() == ElementKind.ENUM_CONSTANT) {
                    values.add(e.getSimpleName().toString());
                }
            }
            s.put("enum", values);
            return;
        }
        if (isA(type, "java.util.Map")) {
            s.put("type", "object");
            if (type.getTypeArguments().size() == 2) {
                s.put("additionalProperties", schema(type.getTypeArguments().get(1), visiting));
            }
            return;
        }
        if (isA(type, "java.lang.Iterable")) {
            s.put("type", "array");
            if (type.getTypeArguments().size() == 1) {
                s.put("items", schema(type.getTypeArguments().get(0), visiting));
            }
            if (isA(type, "java.util.Set")) {
                s.put("uniqueItems", true);
            }
            return;
        }
        if (name.startsWith("java.") || name.startsWith("javax.")) {
            return;
        }
        if (!visiting.add(name)) {
            return; // A recursive type: allow anything below the cycle.
        }
        s.put("type", "object");
        String doc = Docs.of(elements, te);
        if (doc != null) {
            s.put("description", doc);
        }
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (JsonProp p : jsonProperties(te)) {
            Map<String, AnnotationMirror> ann = Mirrors.collect(p.sources());
            Map<String, Object> ps = schema(p.type(), visiting);
            String description = description(ann, p.doc());
            Map<String, Object> withDoc = new LinkedHashMap<>();
            withDoc.putAll(ps);
            if (description != null && !ps.containsKey("description")) {
                withDoc.put("description", description);
            }
            Constraints c = Constraints.read(elements, ann);
            for (String err : c.errors) {
                errors.add(te.getQualifiedName() + "." + p.name() + ": " + err);
            }
            apply(c, withDoc);
            props.put(p.name(), withDoc);
            if (c.requiresValue()) {
                required.add(p.name());
            }
        }
        s.put("properties", props);
        s.put("additionalProperties", false);
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        visiting.remove(name);
    }

    private String description(Map<String, AnnotationMirror> ann, String doc) {
        AnnotationMirror d = ann.get("dev.docuconf.Description");
        if (d == null) {
            d = ann.get("com.fasterxml.jackson.annotation.JsonPropertyDescription");
        }
        return d != null ? Mirrors.string(elements, d, "value") : doc;
    }

    private static void apply(Constraints c, Map<String, Object> s) {
        Object type = s.get("type");
        boolean string = "string".equals(type);
        boolean array = "array".equals(type);
        boolean numeric = "integer".equals(type) || "number".equals(type);
        if (string) {
            Integer min = c.sizeMin;
            if ((c.notBlank || c.notEmpty) && (min == null || min < 1)) {
                min = 1;
            }
            if (min != null) {
                s.put("minLength", (long) min);
            }
            if (c.sizeMax != null) {
                s.put("maxLength", (long) c.sizeMax);
            }
            if (c.pattern != null) {
                s.put("pattern", c.pattern);
            } else if (c.notBlank) {
                s.put("pattern", "\\S");
            }
        } else if (array) {
            Integer min = c.sizeMin;
            if (c.notEmpty && (min == null || min < 1)) {
                min = 1;
            }
            if (min != null) {
                s.put("minItems", (long) min);
            }
            if (c.sizeMax != null) {
                s.put("maxItems", (long) c.sizeMax);
            }
        } else if (numeric) {
            if (c.min != null) {
                s.put(c.minExclusive ? "exclusiveMinimum" : "minimum", number(c.min));
            }
            if (c.max != null) {
                s.put(c.maxExclusive ? "exclusiveMaximum" : "maximum", number(c.max));
            }
        }
    }

    private static Object number(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() <= 0 ? (Object) s.longValueExact() : s;
    }

    private boolean isA(TypeMirror t, String qualified) {
        TypeElement target = elements.getTypeElement(qualified);
        return target != null && types.isAssignable(types.erasure(t), types.erasure(target.asType()));
    }

    /** One property as Jackson sees it. */
    record JsonProp(String name, TypeMirror type, List<Object> sources, String doc) {
    }

    List<JsonProp> jsonProperties(TypeElement te) {
        List<JsonProp> out = new ArrayList<>();
        if (te.getKind() == ElementKind.RECORD) {
            for (RecordComponentElement rc : te.getRecordComponents()) {
                String javaName = rc.getSimpleName().toString();
                VariableElement field = field(te, javaName);
                List<Object> sources = new ArrayList<>(List.of(rc, rc.getAccessor(), rc.asType()));
                if (field != null) {
                    sources.add(field);
                }
                Map<String, AnnotationMirror> ann = Mirrors.collect(sources);
                if (ann.containsKey("com.fasterxml.jackson.annotation.JsonIgnore")) {
                    continue;
                }
                out.add(new JsonProp(jsonName(ann, javaName), rc.asType(), sources,
                        Docs.param(elements, te, javaName)));
            }
            return out;
        }
        List<TypeElement> chain = new ArrayList<>();
        for (TypeElement t = te; t != null && !t.getQualifiedName().contentEquals("java.lang.Object");) {
            chain.add(0, t);
            TypeMirror sup = t.getSuperclass();
            t = sup.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) sup).asElement() : null;
        }
        for (TypeElement t : chain) {
            for (VariableElement f : ElementFilter.fieldsIn(t.getEnclosedElements())) {
                if (f.getModifiers().contains(Modifier.STATIC) || f.getModifiers().contains(Modifier.TRANSIENT)) {
                    continue;
                }
                String javaName = f.getSimpleName().toString();
                ExecutableElement getter = method(t, javaName, true);
                ExecutableElement setter = method(t, javaName, false);
                List<Object> sources = new ArrayList<>();
                sources.add(f);
                sources.add(f.asType());
                if (getter != null) {
                    sources.add(getter);
                }
                if (setter != null) {
                    sources.add(setter);
                }
                Map<String, AnnotationMirror> ann = Mirrors.collect(sources);
                if (ann.containsKey("com.fasterxml.jackson.annotation.JsonIgnore")) {
                    continue;
                }
                boolean visible = setter != null || f.getModifiers().contains(Modifier.PUBLIC)
                        || ann.containsKey("com.fasterxml.jackson.annotation.JsonProperty")
                        || hasLombokSetter(t, f);
                if (!visible) {
                    continue;
                }
                String doc = Docs.of(elements, f);
                if (doc == null) {
                    doc = Docs.of(elements, getter);
                }
                out.add(new JsonProp(jsonName(ann, javaName), f.asType(), sources, doc));
            }
        }
        return out;
    }

    private String jsonName(Map<String, AnnotationMirror> ann, String javaName) {
        AnnotationMirror jp = ann.get("com.fasterxml.jackson.annotation.JsonProperty");
        if (jp != null) {
            String v = Mirrors.string(elements, jp, "value");
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return javaName;
    }

    static VariableElement field(TypeElement te, String name) {
        for (VariableElement f : ElementFilter.fieldsIn(te.getEnclosedElements())) {
            if (f.getSimpleName().contentEquals(name)) {
                return f;
            }
        }
        return null;
    }

    static ExecutableElement method(TypeElement te, String property, boolean getter) {
        String cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (ExecutableElement m : ElementFilter.methodsIn(te.getEnclosedElements())) {
            if (m.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String n = m.getSimpleName().toString();
            if (getter && m.getParameters().isEmpty() && (n.equals("get" + cap) || n.equals("is" + cap))) {
                return m;
            }
            if (!getter && m.getParameters().size() == 1 && n.equals("set" + cap)) {
                return m;
            }
        }
        return null;
    }

    static boolean hasLombokSetter(TypeElement t, VariableElement f) {
        for (Element e : List.of(t, f)) {
            for (AnnotationMirror m : e.getAnnotationMirrors()) {
                String n = m.getAnnotationType().toString();
                if (n.equals("lombok.Data") || n.equals("lombok.Setter")) {
                    return true;
                }
            }
        }
        return false;
    }
}
