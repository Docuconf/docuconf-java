package dev.docuconf.check;

import dev.docuconf.contract.Json;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses structured documents (config files and overlays, SPEC §4.6 and §4.7) in {@code json}, {@code yaml} or
 * {@code toml} into plain data: {@code Map}, {@code List}, {@code String}, {@code Long}, {@code BigDecimal},
 * {@code Boolean} and {@code null}.
 *
 * <p>JSON is read by docuconf itself. YAML and TOML are read with Jackson's YAML and TOML modules, found on the
 * class path at run time (Jackson 3 first, then Jackson 2), so docuconf-core keeps no dependency: an app that
 * declares a YAML or TOML file already has the module its host reads it with.
 */
public final class Documents {

    private Documents() {
    }

    /** The document does not parse; the message never quotes the content. */
    public static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    /** The format needs a parser that is not on the class path. */
    public static final class MissingParser extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        MissingParser(String message) {
            super(message);
        }
    }

    /**
     * Parses a document.
     *
     * @param format {@code json}, {@code yaml} or {@code toml}
     * @param content the bytes, UTF-8; a leading byte order mark is ignored
     * @return plain data; {@code null} for an empty YAML document
     * @throws Malformed when it does not parse
     * @throws MissingParser when YAML or TOML is asked for and Jackson's module for it is not on the class path
     */
    public static Object parse(String format, byte[] content) throws Malformed {
        String f = format == null ? "json" : format.toLowerCase(Locale.ROOT);
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return switch (f) {
            case "json" -> {
                try {
                    yield Json.parse(text);
                } catch (IllegalArgumentException e) {
                    throw new Malformed("is not valid JSON");
                }
            }
            case "yaml", "yml" -> jackson("yaml", text);
            case "toml" -> jackson("toml", text);
            default -> throw new IllegalArgumentException("unknown document format " + format);
        };
    }

    private static final String[][] MAPPERS = {
        {"yaml", "tools.jackson.dataformat.yaml.YAMLMapper"},
        {"yaml", "com.fasterxml.jackson.dataformat.yaml.YAMLMapper"},
        {"toml", "tools.jackson.dataformat.toml.TomlMapper"},
        {"toml", "com.fasterxml.jackson.dataformat.toml.TomlMapper"},
    };

    private static Object jackson(String format, String text) throws Malformed {
        Object mapper = null;
        for (String[] m : MAPPERS) {
            if (m[0].equals(format)) {
                mapper = instantiate(m[1]);
                if (mapper != null) {
                    break;
                }
            }
        }
        if (mapper == null) {
            throw new MissingParser("docuconf: reading " + format.toUpperCase(Locale.ROOT) + " needs Jackson's "
                    + format.toUpperCase(Locale.ROOT) + " module on the class path"
                    + " (tools.jackson.dataformat:jackson-dataformat-" + format
                    + " or com.fasterxml.jackson.dataformat:jackson-dataformat-" + format + ")");
        }
        Object value;
        try {
            Method read = mapper.getClass().getMethod("readValue", String.class, Class.class);
            value = read.invoke(mapper, text, Object.class);
        } catch (InvocationTargetException e) {
            throw new Malformed("is not valid " + format.toUpperCase(Locale.ROOT) + location(e.getCause()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("docuconf: cannot call Jackson's " + format + " reader", e);
        }
        return plain(value);
    }

    private static Object instantiate(String className) {
        for (ClassLoader cl : new ClassLoader[] {Documents.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()}) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(className, true, cl).getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                // Try the next loader, or the next Jackson.
            }
        }
        return null;
    }

    /** {@code (line 3, column 7)} from a Jackson error, never its message, which may quote the content. */
    private static String location(Throwable t) {
        if (t == null) {
            return "";
        }
        try {
            Object loc = t.getClass().getMethod("getLocation").invoke(t);
            if (loc != null) {
                Object line = loc.getClass().getMethod("getLineNr").invoke(loc);
                Object column = loc.getClass().getMethod("getColumnNr").invoke(loc);
                if (line instanceof Integer l && l > 0) {
                    return " (line " + l + ", column " + column + ")";
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No location.
        }
        return "";
    }

    /** Jackson's numbers and collections as docuconf's plain data. */
    private static Object plain(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) {
                out.add(plain(x));
            }
            return out;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger b) {
            return b.bitLength() < 64 ? (Object) b.longValue() : new BigDecimal(b);
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return Double.isFinite(d) ? BigDecimal.valueOf(d) : (Object) d;
        }
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof BigDecimal) {
            return v;
        }
        return v.toString(); // TOML dates and times
    }
}
