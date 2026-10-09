package dev.docuconf.check;

import dev.docuconf.contract.Contract;
import dev.docuconf.contract.OverlaySpec;
import dev.docuconf.contract.VarSpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Reads config-file overlays in contract-first mode (SPEC §4.7): each overlay is an optional file under
 * {@code DOCUCONF_FILE_ROOT}, and a variable's value sits at its {@code configKey}, split on the overlay's
 * {@code keySeparator}, matched exactly. A file that does not parse, or does not hold an object, is
 * {@code file_malformed} for the overlay.
 */
final class Overlays {

    private Overlays() {
    }

    /**
     * A variable's value in an overlay.
     *
     * @param overlay the overlay's name
     * @param value the native value: a string, number, bool, list or object
     */
    record Value(String overlay, Object value) {
    }

    /**
     * Reads every overlay and picks each variable's value. A variable in two overlays takes the first declared.
     * {@code null} values are unset.
     */
    static Map<String, Value> load(Contract contract, Function<String, String> env, List<Violation> out,
            List<String> warnings) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (OverlaySpec o : contract.overlays.values()) {
            Map<String, Object> doc = read(o, path(o, env), out);
            if (doc == null) {
                continue;
            }
            for (VarSpec v : contract.vars.values()) {
                if (v.configKey == null || (contract.profiles != null && v.name.equals(contract.profiles.selector))) {
                    continue;
                }
                Object x = lookup(doc, v.configKey.split(Pattern.quote(o.keySeparator), -1));
                if (x == null) {
                    continue;
                }
                Value previous = values.putIfAbsent(v.name, new Value(o.name, x));
                if (previous != null) {
                    warnings.add(v.name + " is set in overlays " + previous.overlay() + " and " + o.name
                            + "; " + previous.overlay() + " wins");
                }
            }
        }
        return values;
    }

    /** Where an overlay is read from: its path, under {@code DOCUCONF_FILE_ROOT} when that is set. */
    static Path path(OverlaySpec o, Function<String, String> env) {
        String root = env.apply(FileChecker.FILE_ROOT_ENV);
        if (root != null && !root.isEmpty() && o.path.startsWith("/")) {
            return Path.of(root, o.path.substring(1));
        }
        return Path.of(o.path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(OverlaySpec o, Path path, List<Violation> out) {
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | SecurityException e) {
            if (!Files.exists(path)) {
                return null;
            }
            out.add(new Violation(Code.FILE_UNREADABLE, o.name, path + " could not be read"));
            return null;
        }
        Object doc;
        try {
            doc = Documents.parse(o.format, content);
        } catch (Documents.Malformed e) {
            out.add(new Violation(Code.FILE_MALFORMED, o.name, path + " " + e.getMessage()));
            return null;
        }
        if (doc == null) {
            return Map.of(); // an empty YAML file
        }
        if (!(doc instanceof Map<?, ?> m)) {
            out.add(new Violation(Code.FILE_MALFORMED, o.name, path + " does not hold an object at its top level"));
            return null;
        }
        return (Map<String, Object>) m;
    }

    private static Object lookup(Map<String, Object> doc, String[] path) {
        Object at = doc;
        for (String key : path) {
            if (!(at instanceof Map<?, ?> m)) {
                return null;
            }
            at = m.get(key);
        }
        return at;
    }

    /**
     * The wire string a native scalar stands for (SPEC §4.7): a string as it is; a bool as {@code true} or
     * {@code false}; a number with an integral value as a base-10 integer, any other in shortest round-trip
     * decimal.
     *
     * @throws WireFormat.WireException {@code invalid_type} for an object or list where a scalar belongs
     */
    static String wire(Object x, String where) {
        if (x instanceof String s) {
            return s;
        }
        if (x instanceof Boolean b) {
            return b.toString();
        }
        if (x instanceof Long || x instanceof Integer) {
            return x.toString();
        }
        if (x instanceof BigDecimal d) {
            BigDecimal stripped = d.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                return stripped.toBigInteger().toString();
            }
            // BigDecimal.valueOf(double) already holds the shortest round-trip form of a parsed double.
            return stripped.toPlainString();
        }
        if (x instanceof Number n) {
            return wire(new BigDecimal(n.toString()), where);
        }
        String kind = x instanceof Map ? "an object" : x instanceof List ? "a list" : "not a scalar";
        throw new WireFormat.WireException(Code.INVALID_TYPE, "is " + kind + where + ", where a single value"
                + " belongs");
    }
}
