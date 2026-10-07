package dev.docuconf.check;

import dev.docuconf.contract.Json;
import dev.docuconf.contract.Re2;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a JSON value against a JSON Schema, for {@code json} variables in contract-first mode, where there is
 * no Java type for Bean Validation to check.
 *
 * <p>Supports the keywords contract schemas use: {@code type}, {@code enum}, {@code const}, {@code properties},
 * {@code required}, {@code additionalProperties}, {@code patternProperties}, {@code minProperties},
 * {@code maxProperties}, {@code items}, {@code minItems}, {@code maxItems}, {@code uniqueItems},
 * {@code minimum}, {@code maximum}, {@code exclusiveMinimum}, {@code exclusiveMaximum}, {@code multipleOf},
 * {@code minLength}, {@code maxLength}, {@code pattern} (RE2), {@code allOf}, {@code anyOf}, {@code oneOf},
 * {@code not} and local {@code $ref}s ({@code #/$defs/...}, {@code #/definitions/...}). Annotations such as
 * {@code description} and {@code format} are ignored.
 */
public final class JsonSchema {

    private final Map<String, Object> root;
    private final List<String> problems = new ArrayList<>();

    private JsonSchema(Map<String, Object> root) {
        this.root = root;
    }

    /**
     * Validates a value.
     *
     * @param schema the schema
     * @param value the value, as {@link Json#parse(String)} returns it
     * @param secret whether to leave values out of the messages
     * @return the problems, each starting with a JSON path such as {@code $.perMinute}; empty when it is valid
     */
    public static List<String> validate(Map<String, Object> schema, Object value, boolean secret) {
        JsonSchema s = new JsonSchema(schema);
        s.check(schema, value, "$", secret, 0);
        return s.problems;
    }

    @SuppressWarnings("unchecked")
    private void check(Object schemaNode, Object value, String path, boolean secret, int depth) {
        if (depth > 64) {
            problems.add(path + ": schema nests too deeply");
            return;
        }
        if (schemaNode instanceof Boolean b) {
            if (!b) {
                problems.add(path + ": is not allowed");
            }
            return;
        }
        if (!(schemaNode instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> s = (Map<String, Object>) schemaNode;
        String shown = secret ? "" : " (got " + Json.write(value) + ")";
        Object ref = s.get("$ref");
        if (ref instanceof String r) {
            check(resolve(r), value, path, secret, depth + 1);
        }
        Object type = s.get("type");
        if (type != null) {
            List<Object> types = type instanceof List<?> l ? (List<Object>) l : List.of(type);
            boolean any = false;
            for (Object t : types) {
                any |= isType(value, (String) t);
            }
            if (!any) {
                problems.add(path + ": must be " + String.join(" or ", types.stream().map(Object::toString).toList())
                        + shown);
                return;
            }
        }
        if (s.containsKey("enum") && !contains((List<Object>) s.get("enum"), value)) {
            problems.add(path + ": must be one of " + Json.write(s.get("enum")) + shown);
        }
        if (s.containsKey("const") && !same(s.get("const"), value)) {
            problems.add(path + ": must be " + Json.write(s.get("const")) + shown);
        }
        if (value instanceof Map<?, ?> obj) {
            object(s, (Map<String, Object>) obj, path, secret, depth);
        } else if (value instanceof List<?> arr) {
            array(s, (List<Object>) arr, path, secret, depth);
        } else if (value instanceof String str) {
            int len = str.codePointCount(0, str.length());
            Number min = (Number) s.get("minLength");
            Number max = (Number) s.get("maxLength");
            if (min != null && len < min.longValue()) {
                problems.add(path + ": is shorter than " + min + " characters" + shown);
            }
            if (max != null && len > max.longValue()) {
                problems.add(path + ": is longer than " + max + " characters" + shown);
            }
            if (s.get("pattern") instanceof String p && !Re2.compile(p).matcher(str).find()) {
                problems.add(path + ": does not match " + p + shown);
            }
        } else if (value instanceof Number n) {
            number(s, decimal(n), path, shown);
        }
        for (String k : List.of("allOf", "anyOf", "oneOf")) {
            if (!(s.get(k) instanceof List<?> subs)) {
                continue;
            }
            int passed = 0;
            List<String> first = null;
            for (Object sub : subs) {
                JsonSchema inner = new JsonSchema(root);
                inner.check(sub, value, path, secret, depth + 1);
                if (inner.problems.isEmpty()) {
                    passed++;
                } else if (first == null) {
                    first = inner.problems;
                }
            }
            if (k.equals("allOf") && passed < subs.size()) {
                problems.addAll(first);
            } else if (k.equals("anyOf") && passed == 0) {
                problems.add(path + ": matches none of the anyOf schemas");
            } else if (k.equals("oneOf") && passed != 1) {
                problems.add(path + ": matches " + passed + " of the oneOf schemas, not exactly one");
            }
        }
        if (s.containsKey("not")) {
            JsonSchema inner = new JsonSchema(root);
            inner.check(s.get("not"), value, path, secret, depth + 1);
            if (inner.problems.isEmpty()) {
                problems.add(path + ": matches a schema it must not");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void object(Map<String, Object> s, Map<String, Object> obj, String path, boolean secret, int depth) {
        Map<String, Object> props = (Map<String, Object>) s.getOrDefault("properties", Map.of());
        Map<String, Object> patterns = (Map<String, Object>) s.getOrDefault("patternProperties", Map.of());
        if (s.get("required") instanceof List<?> req) {
            for (Object r : req) {
                if (!obj.containsKey((String) r)) {
                    problems.add(path + ": is missing required property " + r);
                }
            }
        }
        Number minP = (Number) s.get("minProperties");
        Number maxP = (Number) s.get("maxProperties");
        if (minP != null && obj.size() < minP.longValue()) {
            problems.add(path + ": has fewer than " + minP + " properties");
        }
        if (maxP != null && obj.size() > maxP.longValue()) {
            problems.add(path + ": has more than " + maxP + " properties");
        }
        Object additional = s.get("additionalProperties");
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            String child = path + "." + e.getKey();
            boolean matched = false;
            if (props.containsKey(e.getKey())) {
                matched = true;
                check(props.get(e.getKey()), e.getValue(), child, secret, depth + 1);
            }
            for (Map.Entry<String, Object> p : patterns.entrySet()) {
                if (Re2.compile(p.getKey()).matcher(e.getKey()).find()) {
                    matched = true;
                    check(p.getValue(), e.getValue(), child, secret, depth + 1);
                }
            }
            if (!matched && additional != null) {
                if (Boolean.FALSE.equals(additional)) {
                    problems.add(path + ": property " + e.getKey() + " is not allowed");
                } else {
                    check(additional, e.getValue(), child, secret, depth + 1);
                }
            }
        }
    }

    private void array(Map<String, Object> s, List<Object> arr, String path, boolean secret, int depth) {
        Number min = (Number) s.get("minItems");
        Number max = (Number) s.get("maxItems");
        if (min != null && arr.size() < min.longValue()) {
            problems.add(path + ": has fewer than " + min + " items");
        }
        if (max != null && arr.size() > max.longValue()) {
            problems.add(path + ": has more than " + max + " items");
        }
        if (Boolean.TRUE.equals(s.get("uniqueItems"))) {
            Set<String> seen = new HashSet<>();
            for (Object o : arr) {
                if (!seen.add(Json.write(normalize(o)))) {
                    problems.add(path + ": has duplicate items");
                    break;
                }
            }
        }
        Object items = s.get("items");
        if (items != null) {
            for (int i = 0; i < arr.size(); i++) {
                check(items, arr.get(i), path + "[" + i + "]", secret, depth + 1);
            }
        }
    }

    private void number(Map<String, Object> s, BigDecimal x, String path, String shown) {
        if (s.get("minimum") instanceof Number m && x.compareTo(decimal(m)) < 0) {
            problems.add(path + ": is below minimum " + m + shown);
        }
        if (s.get("maximum") instanceof Number m && x.compareTo(decimal(m)) > 0) {
            problems.add(path + ": is above maximum " + m + shown);
        }
        if (s.get("exclusiveMinimum") instanceof Number m && x.compareTo(decimal(m)) <= 0) {
            problems.add(path + ": must be above " + m + shown);
        }
        if (s.get("exclusiveMaximum") instanceof Number m && x.compareTo(decimal(m)) >= 0) {
            problems.add(path + ": must be below " + m + shown);
        }
        if (s.get("multipleOf") instanceof Number m && decimal(m).signum() > 0
                && x.remainder(decimal(m)).signum() != 0) {
            problems.add(path + ": is not a multiple of " + m + shown);
        }
    }

    @SuppressWarnings("unchecked")
    private Object resolve(String ref) {
        if (ref.equals("#")) {
            return root;
        }
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("only local $refs are supported: " + ref);
        }
        Object node = root;
        for (String part : ref.substring(2).split("/")) {
            String key = part.replace("~1", "/").replace("~0", "~");
            if (!(node instanceof Map<?, ?> m) || !m.containsKey(key)) {
                throw new IllegalArgumentException("$ref " + ref + " does not resolve");
            }
            node = ((Map<String, Object>) m).get(key);
        }
        return node;
    }

    private static boolean isType(Object value, String type) {
        return switch (type) {
            case "null" -> value == null;
            case "boolean" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && isIntegral(decimal(n));
            default -> false;
        };
    }

    private static boolean isIntegral(BigDecimal d) {
        return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal decimal(Number n) {
        return n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
    }

    private static boolean contains(List<Object> values, Object value) {
        for (Object v : values) {
            if (same(v, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(Object a, Object b) {
        return Json.write(normalize(a)).equals(Json.write(normalize(b)));
    }

    /** Numbers compared by value, so {@code 1} equals {@code 1.0}. */
    @SuppressWarnings("unchecked")
    private static Object normalize(Object o) {
        if (o instanceof Number n) {
            BigDecimal d = decimal(n);
            return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        }
        if (o instanceof List<?> l) {
            return l.stream().map(JsonSchema::normalize).toList();
        }
        if (o instanceof Map<?, ?> m) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> sorted.put(k, normalize(v)));
            return sorted;
        }
        return o;
    }
}
