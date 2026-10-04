package dev.docuconf.processor;

import dev.docuconf.contract.GoDuration;
import dev.docuconf.contract.Json;
import dev.docuconf.contract.VarSpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts defaults (field initializers, {@code @DefaultValue} strings, {@code application.yml} values) to
 * contract data, the way Spring Boot's binder would convert them.
 */
final class Values {

    private static final Pattern SIMPLE_DURATION = Pattern.compile("^([+-]?\\d+)([a-zA-Z]{0,2})$");
    private static final Pattern ISO_DURATION = Pattern.compile("^[+-]?[pP].*$");

    private Values() {
    }

    static Object toContract(VarSpec v, Object raw, String durationUnit) {
        if (raw instanceof Initializers.EnumConstant e) {
            raw = e.name();
        }
        switch (v.type) {
            case STRING:
            case URL:
                if (raw instanceof List || raw instanceof java.util.Map) {
                    throw new IllegalArgumentException("expected a single value");
                }
                return String.valueOf(raw);
            case INT:
                return integer(raw);
            case FLOAT:
                if (raw instanceof Number n) {
                    return new BigDecimal(n.toString());
                }
                try {
                    return new BigDecimal(raw.toString().trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + raw + "\" is not a number");
                }
            case BOOL:
                if (raw instanceof Boolean b) {
                    return b;
                }
                String s = raw.toString().trim().toLowerCase(Locale.ROOT);
                return switch (s) {
                    case "true", "on", "yes", "1" -> true;
                    case "false", "off", "no", "0" -> false;
                    default -> throw new IllegalArgumentException("\"" + raw + "\" is not a boolean");
                };
            case DURATION:
                return GoDuration.format(duration(raw, durationUnit));
            case ENUM:
                return enumValue(v.values, raw.toString());
            case LIST: {
                List<?> items;
                if (raw instanceof List<?> l) {
                    items = l;
                    // @DefaultValue({"a,b"}) and a yml string both go through Spring's delimited conversion.
                    if (l.size() == 1 && l.get(0) instanceof String one) {
                        items = split(one, v.separator);
                    }
                } else {
                    items = split(raw.toString(), v.separator);
                }
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    out.add("int".equals(v.items) ? integer(item) : String.valueOf(item));
                }
                return out;
            }
            case JSON:
                if (raw instanceof String js) {
                    return Json.parse(js);
                }
                throw new IllegalArgumentException("a json variable's default must be a JSON string");
            default:
                throw new IllegalArgumentException("unsupported type");
        }
    }

    private static List<String> split(String s, String separator) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) {
            return out;
        }
        for (String part : s.split(Pattern.quote(separator), -1)) {
            out.add(part.trim());
        }
        return out;
    }

    private static Long integer(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof BigInteger b) {
            return b.longValueExact();
        }
        if (raw instanceof Character c) {
            return (long) c;
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + raw + "\" is not a 64-bit integer");
        }
    }

    private static String enumValue(List<String> values, String raw) {
        if (values.contains(raw)) {
            return raw;
        }
        // Spring's lenient enum conversion ignores case, dashes and underscores.
        String wanted = raw.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                return value;
            }
        }
        throw new IllegalArgumentException("\"" + raw + "\" is not one of " + values);
    }

    /** Spring's DurationStyle: ISO-8601, or a number with an optional single unit (ns, us, ms, s, m, h, d). */
    static Duration duration(Object raw, String unit) {
        if (raw instanceof Duration d) {
            return d;
        }
        ChronoUnit defaultUnit = unit == null ? ChronoUnit.MILLIS : ChronoUnit.valueOf(unit);
        if (raw instanceof Number n) {
            return Duration.of(n.longValue(), defaultUnit);
        }
        String s = raw.toString().trim();
        if (ISO_DURATION.matcher(s).matches()) {
            try {
                return Duration.parse(s);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("\"" + s + "\" is not an ISO-8601 duration");
            }
        }
        Matcher m = SIMPLE_DURATION.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("\"" + s + "\" is not a duration Spring can parse (\"30s\" or"
                    + " \"PT30S\")");
        }
        long amount = Long.parseLong(m.group(1));
        String suffix = m.group(2).toLowerCase(Locale.ROOT);
        ChronoUnit u = switch (suffix) {
            case "" -> defaultUnit;
            case "ns" -> ChronoUnit.NANOS;
            case "us" -> ChronoUnit.MICROS;
            case "ms" -> ChronoUnit.MILLIS;
            case "s" -> ChronoUnit.SECONDS;
            case "m" -> ChronoUnit.MINUTES;
            case "h" -> ChronoUnit.HOURS;
            case "d" -> ChronoUnit.DAYS;
            default -> throw new IllegalArgumentException("unknown duration unit in \"" + s + "\"");
        };
        return Duration.of(amount, u);
    }
}
