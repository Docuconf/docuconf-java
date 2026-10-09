package dev.docuconf.contract;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations in the contract's Go syntax: integer components only, such as {@code 1h30m} or {@code 1500ms}. */
public final class GoDuration {

    private static final Pattern WHOLE = Pattern.compile("^([0-9]+(ns|us|ms|s|m|h))+$");
    private static final Pattern PART = Pattern.compile("([0-9]+)(ns|us|ms|s|m|h)");

    private GoDuration() {
    }

    /**
     * Parses a contract duration.
     *
     * @param text such as {@code 1h30m}
     * @return the duration
     * @throws IllegalArgumentException if it is not in contract syntax
     */
    public static Duration parse(String text) {
        if (text == null || !WHOLE.matcher(text).matches()) {
            throw new IllegalArgumentException("not a duration in Go syntax (such as 1m30s): " + text);
        }
        Duration total = Duration.ZERO;
        Matcher m = PART.matcher(text);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "ns" -> Duration.ofNanos(n);
                case "us" -> Duration.ofNanos(Math.multiplyExact(n, 1000L));
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                default -> Duration.ofHours(n);
            });
        }
        return total;
    }

    /**
     * Whether text is a contract duration.
     *
     * @param text the text
     * @return whether {@link #parse(String)} accepts it
     */
    public static boolean isValid(String text) {
        return text != null && WHOLE.matcher(text).matches();
    }

    /**
     * Writes a duration in canonical form: largest units first, zero components left out ({@code 1h30m},
     * {@code 0s}).
     *
     * @param d a non-negative duration
     * @return the canonical text
     * @throws IllegalArgumentException if {@code d} is negative
     */
    public static String format(Duration d) {
        if (d.isNegative()) {
            if (d.equals(Duration.ofSeconds(Long.MIN_VALUE))) {
                throw new IllegalArgumentException("duration out of range: " + d);
            }
            return "-" + format(d.negated());
        }
        if (d.isZero()) {
            return "0s";
        }
        StringBuilder b = new StringBuilder();
        long hours = d.toHours();
        if (hours > 0) {
            b.append(hours).append('h');
        }
        int minutes = d.toMinutesPart();
        if (minutes > 0) {
            b.append(minutes).append('m');
        }
        int seconds = d.toSecondsPart();
        if (seconds > 0) {
            b.append(seconds).append('s');
        }
        int nanos = d.toNanosPart();
        int ms = nanos / 1_000_000;
        int us = (nanos / 1000) % 1000;
        int ns = nanos % 1000;
        if (ms > 0) {
            b.append(ms).append("ms");
        }
        if (us > 0) {
            b.append(us).append("us");
        }
        if (ns > 0) {
            b.append(ns).append("ns");
        }
        return b.toString();
    }

    /**
     * Rewrites a contract duration in canonical form.
     *
     * @param text such as {@code 90m}
     * @return such as {@code 1h30m}
     */
    public static String canonical(String text) {
        return format(parse(text));
    }

    /**
     * Writes a duration in the {@code iso8601} wire encoding the platform renders (SPEC §5): whole and fractional
     * seconds, such as {@code PT90S} or {@code PT0.25S}.
     *
     * @param d the duration, with at most millisecond precision
     * @return the ISO-8601 text
     */
    public static String iso8601(Duration d) {
        long ms = d.toMillis();
        long secs = ms / 1000;
        long frac = ms % 1000;
        String f = frac == 0 ? "" : ("." + String.format("%03d", frac)).replaceAll("0+$", "");
        return "PT" + secs + f + "S";
    }
}
