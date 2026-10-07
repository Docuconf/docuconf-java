package dev.docuconf.check;

import dev.docuconf.contract.GoDuration;
import dev.docuconf.contract.Re2;
import dev.docuconf.contract.VarSpec;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks a typed value against its variable's contract constraints. Used for defaults and profile values at
 * compile time, and for the real values at startup, so both apply the same rules.
 *
 * <p>Values are in contract form: {@code String} for string, url and enum; {@code Long} or {@code Integer} for
 * int; {@code BigDecimal} or {@code Double} for float; {@code Boolean}; {@link Duration}; a {@code List} for lists.
 */
public final class VarChecker {

    private static final Pattern URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+$");

    private VarChecker() {
    }

    /**
     * Checks one value.
     *
     * @param v the variable
     * @param value the value, not {@code null}
     * @return the violations, empty when it is valid
     */
    public static List<Violation> check(VarSpec v, Object value) {
        List<Violation> out = new ArrayList<>();
        String shown = v.secret ? "" : " (got " + show(value) + ")";
        switch (v.type) {
            case STRING -> {
                String s = (String) value;
                int len = s.codePointCount(0, s.length());
                if (v.minLength != null && len < v.minLength) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name,
                            "is shorter than " + v.minLength + " characters" + shown));
                }
                if (v.maxLength != null && len > v.maxLength) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name,
                            "is longer than " + v.maxLength + " characters" + shown));
                }
                if (v.pattern != null && !Re2.compile(v.pattern).matcher(s).find()) {
                    out.add(new Violation(Code.PATTERN_MISMATCH, v.name, "does not match " + v.pattern + shown));
                }
            }
            case INT -> {
                long n = ((Number) value).longValue();
                if (v.min != null && n < ((Number) v.min).longValue()) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "is below min " + v.min + shown));
                }
                if (v.max != null && n > ((Number) v.max).longValue()) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "is above max " + v.max + shown));
                }
            }
            case FLOAT -> {
                double d = ((Number) value).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    out.add(new Violation(Code.INVALID_TYPE, v.name, "must be a finite number" + shown));
                    break;
                }
                BigDecimal x = decimal(value);
                if (v.min != null && x.compareTo(decimal(v.min)) < 0) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "is below min " + v.min + shown));
                }
                if (v.max != null && x.compareTo(decimal(v.max)) > 0) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "is above max " + v.max + shown));
                }
            }
            case DURATION -> {
                Duration d = (Duration) value;
                if (d.isNegative()) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "must not be negative" + shown));
                    break;
                }
                if (v.min != null && d.compareTo(GoDuration.parse((String) v.min)) < 0) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "is below min " + v.min + shown));
                }
                if (v.max != null && d.compareTo(GoDuration.parse((String) v.max)) > 0) {
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "is above max " + v.max + shown));
                }
            }
            case URL -> {
                String s = value.toString();
                if (!URL.matcher(s).matches()) {
                    out.add(new Violation(Code.INVALID_TYPE, v.name, "is not a URL of the form scheme://..." + shown));
                } else if (v.schemes != null && !v.schemes.isEmpty()) {
                    String scheme = s.substring(0, s.indexOf(':'));
                    if (!v.schemes.contains(scheme)) {
                        out.add(new Violation(Code.INVALID_SCHEME, v.name,
                                "must use one of the schemes " + String.join(", ", v.schemes)
                                        + (v.secret ? "" : " (got " + scheme + ")")));
                    }
                }
            }
            case ENUM -> {
                if (!v.values.contains(value.toString())) {
                    out.add(new Violation(Code.NOT_IN_ENUM, v.name,
                            "must be one of " + String.join(", ", v.values) + shown));
                }
            }
            case LIST -> {
                List<?> l = (List<?>) value;
                if (v.minItems != null && l.size() < v.minItems) {
                    out.add(new Violation(Code.TOO_FEW_ITEMS, v.name,
                            "has " + l.size() + " items; at least " + v.minItems + " required"));
                }
                if (v.maxItems != null && l.size() > v.maxItems) {
                    out.add(new Violation(Code.TOO_MANY_ITEMS, v.name,
                            "has " + l.size() + " items; at most " + v.maxItems + " allowed"));
                }
                if ("int".equals(v.items)) {
                    for (int i = 0; i < l.size(); i++) {
                        Object o = l.get(i);
                        String got = v.secret ? "" : " (got " + show(o) + ")";
                        if (!(o instanceof Long || o instanceof Integer)) {
                            out.add(new Violation(Code.INVALID_TYPE, v.name, "items must be integers" + got));
                            break;
                        }
                        long n = ((Number) o).longValue();
                        if (v.itemMin != null && n < v.itemMin) {
                            out.add(new Violation(Code.OUT_OF_RANGE, v.name,
                                    "item " + i + " is below itemMin " + v.itemMin + got));
                            break;
                        }
                        if (v.itemMax != null && n > v.itemMax) {
                            out.add(new Violation(Code.OUT_OF_RANGE, v.name,
                                    "item " + i + " is above itemMax " + v.itemMax + got));
                            break;
                        }
                    }
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static BigDecimal decimal(Object o) {
        return o instanceof BigDecimal d ? d : new BigDecimal(o.toString());
    }

    private static String show(Object value) {
        if (value instanceof String s) {
            StringBuilder b = new StringBuilder();
            dev.docuconf.contract.Json.quote(b, s);
            return b.toString();
        }
        if (value instanceof Duration d) {
            return d.isNegative() ? d.toString() : GoDuration.format(d);
        }
        return String.valueOf(value);
    }
}
