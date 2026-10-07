package dev.docuconf.check;

import dev.docuconf.contract.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses environment values in the contract's wire encodings (SPEC §5): integers, floats, bools, the four duration
 * encodings and the three list encodings. Parsing never depends on the process locale.
 *
 * <p>Each parser throws {@link WireException} with the stable code to report: {@code invalid_type} for text that
 * is not of the type, {@code out_of_range} for an integer outside the 64-bit range.
 */
public final class WireFormat {

    /** The largest duration, as Go's {@code time.Duration} holds it, in nanoseconds. */
    private static final BigDecimal MAX_DURATION_NANOS = BigDecimal.valueOf(Long.MAX_VALUE);

    private static final Pattern INTEGER = Pattern.compile("^[+-]?[0-9]+$");
    private static final Pattern FLOAT = Pattern.compile("^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?$");
    private static final Pattern GO_PART = Pattern.compile("([0-9]*)(?:\\.([0-9]*))?(ns|us|µs|μs|ms|s|m|h)");
    private static final String ISO_NUM = "([0-9]+(?:[.,][0-9]+)?)";
    private static final Pattern ISO8601 = Pattern.compile(
            "^P(?:" + ISO_NUM + "D)?(?:T(?:" + ISO_NUM + "H)?(?:" + ISO_NUM + "M)?(?:" + ISO_NUM + "S)?)?$");
    private static final Pattern SECONDS = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?$");
    private static final Pattern TIMESPAN = Pattern.compile(
            "^(?:([0-9]+)\\.)?([0-9]{1,2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,7}))?$");

    private WireFormat() {
    }

    /** A value that does not parse, with the code to report. */
    public static final class WireException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final Code code;

        /**
         * Creates the exception.
         *
         * @param code {@link Code#INVALID_TYPE} or {@link Code#OUT_OF_RANGE}
         * @param message what is wrong, without the value
         */
        public WireException(Code code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * The code to report.
         *
         * @return the code
         */
        public Code code() {
            return code;
        }
    }

    /**
     * Whether text is a base-10 integer, of any size.
     *
     * @param raw the text
     * @return whether it is an optional sign and digits
     */
    public static boolean isInteger(String raw) {
        return raw != null && INTEGER.matcher(raw).matches();
    }

    /**
     * Parses a 64-bit integer.
     *
     * @param raw the text
     * @return the value
     * @throws WireException {@code invalid_type} for a non-integer, {@code out_of_range} beyond 64 bits
     */
    public static long parseInt(String raw) {
        if (!isInteger(raw)) {
            throw new WireException(Code.INVALID_TYPE, "is not an integer");
        }
        BigInteger n = new BigInteger(raw.startsWith("+") ? raw.substring(1) : raw);
        if (n.bitLength() >= 64) {
            throw new WireException(Code.OUT_OF_RANGE, "is outside the 64-bit integer range");
        }
        return n.longValue();
    }

    /**
     * Parses a finite float, such as {@code 0.5}, {@code -1.75} or {@code 1e3}. A decimal comma is not accepted,
     * whatever the locale.
     *
     * @param raw the text
     * @return the exact decimal value
     * @throws WireException {@code invalid_type} for anything else, including {@code NaN} and infinities
     */
    public static BigDecimal parseFloat(String raw) {
        if (raw == null || !FLOAT.matcher(raw).matches()) {
            throw new WireException(Code.INVALID_TYPE, "is not a finite number");
        }
        BigDecimal d = new BigDecimal(raw.startsWith("+") ? raw.substring(1) : raw);
        if (Double.isInfinite(d.doubleValue())) {
            throw new WireException(Code.INVALID_TYPE, "is not a finite number");
        }
        return d;
    }

    /**
     * Parses a bool: {@code true} or {@code false}, in any case.
     *
     * @param raw the text
     * @return the value
     * @throws WireException {@code invalid_type} for anything else
     */
    public static boolean parseBool(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new WireException(Code.INVALID_TYPE, "is not a bool (true or false)");
    }

    /**
     * Parses a duration in an encoding.
     *
     * @param raw the text
     * @param encoding {@code go} (or {@code null}), {@code iso8601}, {@code seconds} or {@code timespan}
     * @return the duration
     * @throws WireException {@code invalid_type} when the text is not in the encoding
     */
    public static Duration parseDuration(String raw, String encoding) {
        String enc = encoding == null ? "go" : encoding;
        BigDecimal nanos = switch (enc) {
            case "go" -> goNanos(raw);
            case "iso8601" -> iso8601Nanos(raw);
            case "seconds" -> raw != null && SECONDS.matcher(raw).matches()
                    ? new BigDecimal(raw).multiply(BigDecimal.valueOf(1_000_000_000L)) : null;
            case "timespan" -> timespanNanos(raw);
            default -> throw new IllegalArgumentException("unknown duration encoding " + enc);
        };
        if (nanos == null || nanos.abs().compareTo(MAX_DURATION_NANOS) > 0) {
            throw new WireException(Code.INVALID_TYPE, "is not a duration " + hint(enc));
        }
        return Duration.ofNanos(nanos.longValue());
    }

    private static String hint(String encoding) {
        return switch (encoding) {
            case "iso8601" -> "in ISO 8601 form such as PT90S";
            case "seconds" -> "in seconds such as 90 or 1.5";
            case "timespan" -> "of the form [d.]hh:mm:ss[.fff] such as 00:01:30";
            default -> "such as 1m30s";
        };
    }

    /** Go's {@code time.ParseDuration}: a signed sequence of decimal numbers with units, or {@code 0}. */
    private static BigDecimal goNanos(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw;
        boolean negative = false;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            negative = s.charAt(0) == '-';
            s = s.substring(1);
        }
        if (s.equals("0")) {
            return BigDecimal.ZERO;
        }
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = GO_PART.matcher(s);
        BigDecimal total = BigDecimal.ZERO;
        int at = 0;
        while (at < s.length()) {
            if (!m.find(at) || m.start() != at) {
                return null;
            }
            String whole = m.group(1);
            String frac = m.group(2) == null ? "" : m.group(2);
            if (whole.isEmpty() && frac.isEmpty()) {
                return null;
            }
            BigDecimal n = new BigDecimal((whole.isEmpty() ? "0" : whole) + (frac.isEmpty() ? "" : "." + frac));
            long unit = switch (m.group(3)) {
                case "ns" -> 1L;
                case "us", "µs", "μs" -> 1_000L;
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60_000_000_000L;
                default -> 3_600_000_000_000L;
            };
            total = total.add(n.multiply(BigDecimal.valueOf(unit)));
            at = m.end();
        }
        total = total.setScale(0, java.math.RoundingMode.DOWN);
        return negative ? total.negate() : total;
    }

    /** Days, hours, minutes and seconds, as {@code #Render} emits them ({@code PT90S}, {@code PT1.5S}). */
    private static BigDecimal iso8601Nanos(String raw) {
        if (raw == null || raw.equals("P") || raw.endsWith("T")) {
            return null;
        }
        Matcher m = ISO8601.matcher(raw);
        if (!m.matches()) {
            return null;
        }
        long[] units = {86_400_000_000_000L, 3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L};
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < units.length; i++) {
            String g = m.group(i + 1);
            if (g != null) {
                total = total.add(new BigDecimal(g.replace(',', '.')).multiply(BigDecimal.valueOf(units[i])));
            }
        }
        return total.setScale(0, java.math.RoundingMode.DOWN);
    }

    /** .NET's {@code TimeSpan} constant format, {@code [d.]hh:mm:ss[.fffffff]}. */
    private static BigDecimal timespanNanos(String raw) {
        Matcher m = raw == null ? null : TIMESPAN.matcher(raw);
        if (m == null || !m.matches()) {
            return null;
        }
        int hh = Integer.parseInt(m.group(2));
        int mm = Integer.parseInt(m.group(3));
        int ss = Integer.parseInt(m.group(4));
        if (hh > 23 || mm > 59 || ss > 59) {
            return null;
        }
        BigDecimal total = new BigDecimal(m.group(1) == null ? "0" : m.group(1)).multiply(
                BigDecimal.valueOf(86_400_000_000_000L));
        total = total.add(BigDecimal.valueOf(hh * 3_600_000_000_000L + mm * 60_000_000_000L + ss * 1_000_000_000L));
        if (m.group(5) != null) {
            total = total.add(new BigDecimal("0." + m.group(5)).multiply(BigDecimal.valueOf(1_000_000_000L)));
        }
        return total.setScale(0, java.math.RoundingMode.DOWN);
    }

    /**
     * Splits a {@code csv} list. Items are not trimmed.
     *
     * @param raw the value
     * @param separator the separator, {@code ,} when {@code null}
     * @return the items
     */
    public static List<String> splitCsv(String raw, String separator) {
        String sep = separator == null || separator.isEmpty() ? "," : separator;
        return new ArrayList<>(Arrays.asList(raw.split(Pattern.quote(sep), -1)));
    }

    /**
     * Reads a {@code json} list: a JSON array of strings, or of integers.
     *
     * @param raw the value
     * @param items {@code string} or {@code int}
     * @return the items, as {@code String} or {@code Long}
     * @throws WireException {@code invalid_type} for anything else, {@code out_of_range} for an integer item beyond
     *     64 bits
     */
    public static List<Object> parseJsonList(String raw, String items) {
        Object doc;
        try {
            doc = Json.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new WireException(Code.INVALID_TYPE, "is not a JSON array");
        }
        if (!(doc instanceof List<?> list)) {
            throw new WireException(Code.INVALID_TYPE, "is not a JSON array");
        }
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object x = list.get(i);
            if ("int".equals(items)) {
                if (x instanceof Long) {
                    out.add(x);
                    continue;
                }
                if (x instanceof BigDecimal d && d.scale() == 0) {
                    throw new WireException(Code.OUT_OF_RANGE, "item " + i + " is outside the 64-bit integer range");
                }
                throw new WireException(Code.INVALID_TYPE, "item " + i + " is " + kind(x) + ", not an integer");
            }
            if (!(x instanceof String)) {
                throw new WireException(Code.INVALID_TYPE, "item " + i + " is " + kind(x) + ", not a string");
            }
            out.add(x);
        }
        return out;
    }

    private static String kind(Object x) {
        if (x == null) {
            return "null";
        }
        if (x instanceof String) {
            return "a string";
        }
        if (x instanceof Number) {
            return "a number";
        }
        if (x instanceof Boolean) {
            return "a bool";
        }
        return x instanceof List ? "an array" : "an object";
    }

    /**
     * Names an indexed list's item variable.
     *
     * @param name the list variable
     * @param index the item index
     * @return {@code NAME__index}
     */
    public static String indexedName(String name, int index) {
        return name + "__" + index;
    }
    /**
     * Whether an indexed list suffix is an item index (SPEC §5): a decimal number with no leading zero.
     *
     * @param suffix the text after {@code NAME__}
     * @return whether it names an item
     */
    public static boolean isIndex(String suffix) {
        if (suffix.isEmpty() || (suffix.length() > 1 && suffix.charAt(0) == '0')) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Describes a gap in an indexed list (SPEC §5): its items must be numbered from 0 with no gap.
     *
     * @param prefix the item name before the index, such as {@code NAME__}
     * @param indices the indices that are set, each one {@link #isIndex(String) an index}
     * @return the message, naming the first missing item and the highest one that is set
     */
    public static String gapMessage(String prefix, Collection<String> indices) {
        int missing = 0;
        while (indices.contains(Integer.toString(missing))) {
            missing++;
        }
        String highest = indices.stream()
                .max((a, b) -> a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b))
                .orElse("0");
        return "is an indexed list with a gap: " + prefix + highest + " is set but " + prefix + missing
                + " is not; number the items from 0 with no gap";
    }
}
