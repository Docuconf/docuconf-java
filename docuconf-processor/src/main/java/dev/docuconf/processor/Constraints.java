package dev.docuconf.processor;

import dev.docuconf.contract.Re2;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.util.Elements;

/** Jakarta Bean Validation (and Hibernate Validator) constraints on one property, read by name. */
final class Constraints {

    private static final String BV = "jakarta.validation.constraints.";
    private static final String HV = "org.hibernate.validator.constraints.";

    boolean notNull;
    boolean notBlank;
    boolean notEmpty;
    Integer sizeMin;
    Integer sizeMax;
    BigDecimal min;
    boolean minExclusive;
    BigDecimal max;
    boolean maxExclusive;
    /** The {@code @Pattern}, anchored for the contract. */
    String pattern;
    Duration durationMin;
    Duration durationMax;
    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    static Constraints read(Elements elements, Map<String, AnnotationMirror> a) {
        Constraints c = new Constraints();
        c.notNull = a.containsKey(BV + "NotNull");
        c.notBlank = a.containsKey(BV + "NotBlank");
        c.notEmpty = a.containsKey(BV + "NotEmpty");
        for (String size : new String[] {BV + "Size", HV + "Length"}) {
            AnnotationMirror m = a.get(size);
            if (m != null) {
                long lo = Mirrors.number(elements, m, "min");
                long hi = Mirrors.number(elements, m, "max");
                if (lo > 0) {
                    c.sizeMin = (int) lo;
                }
                if (hi != Integer.MAX_VALUE) {
                    c.sizeMax = (int) hi;
                }
            }
        }
        AnnotationMirror m;
        if ((m = a.get(BV + "Min")) != null) {
            c.lower(BigDecimal.valueOf(Mirrors.number(elements, m, "value")), false);
        }
        if ((m = a.get(BV + "Max")) != null) {
            c.upper(BigDecimal.valueOf(Mirrors.number(elements, m, "value")), false);
        }
        if ((m = a.get(BV + "DecimalMin")) != null) {
            c.lower(new BigDecimal(Mirrors.string(elements, m, "value")), !Mirrors.bool(elements, m, "inclusive"));
        }
        if ((m = a.get(BV + "DecimalMax")) != null) {
            c.upper(new BigDecimal(Mirrors.string(elements, m, "value")), !Mirrors.bool(elements, m, "inclusive"));
        }
        if ((m = a.get(HV + "Range")) != null) {
            c.lower(BigDecimal.valueOf(Mirrors.number(elements, m, "min")), false);
            c.upper(BigDecimal.valueOf(Mirrors.number(elements, m, "max")), false);
        }
        if (a.containsKey(BV + "Positive")) {
            c.lower(BigDecimal.ZERO, true);
        }
        if (a.containsKey(BV + "PositiveOrZero")) {
            c.lower(BigDecimal.ZERO, false);
        }
        if (a.containsKey(BV + "Negative")) {
            c.upper(BigDecimal.ZERO, true);
        }
        if (a.containsKey(BV + "NegativeOrZero")) {
            c.upper(BigDecimal.ZERO, false);
        }
        if (a.containsKey(BV + "Pattern#multiple")) {
            c.errors.add("has more than one @Pattern; a contract variable has one pattern, so combine them");
        }
        if ((m = a.get(BV + "Pattern")) != null) {
            String regexp = Mirrors.string(elements, m, "regexp");
            StringBuilder flags = new StringBuilder();
            for (String flag : Mirrors.strings(elements, m, "flags")) {
                switch (flag) {
                    case "CASE_INSENSITIVE" -> flags.append('i');
                    case "MULTILINE" -> flags.append('m');
                    case "DOTALL" -> flags.append('s');
                    default -> c.errors.add("@Pattern flag " + flag + " has no RE2 equivalent");
                }
            }
            String err = Re2.check(regexp);
            if (err != null) {
                c.errors.add("@Pattern(\"" + regexp + "\") " + err + " (contract patterns are RE2, SPEC §4.3)");
            } else {
                // @Pattern matches the whole value; contract patterns match anywhere (SPEC §4.3).
                c.pattern = Re2.anchor(regexp, flags.toString());
            }
        }
        if ((m = a.get(HV + "time.DurationMin")) != null) {
            c.durationMin = duration(elements, m);
            if (!Mirrors.bool(elements, m, "inclusive")) {
                c.warnings.add("@DurationMin(inclusive = false) is exported as an inclusive bound");
            }
        }
        if ((m = a.get(HV + "time.DurationMax")) != null) {
            c.durationMax = duration(elements, m);
            if (!Mirrors.bool(elements, m, "inclusive")) {
                c.warnings.add("@DurationMax(inclusive = false) is exported as an inclusive bound");
            }
        }
        return c;
    }

    /** Whether the value must be present. */
    boolean requiresValue() {
        return notNull || notBlank || notEmpty;
    }

    private void lower(BigDecimal v, boolean exclusive) {
        if (min == null || v.compareTo(min) > 0 || (v.compareTo(min) == 0 && exclusive)) {
            min = v;
            minExclusive = exclusive;
        }
    }

    private void upper(BigDecimal v, boolean exclusive) {
        if (max == null || v.compareTo(max) < 0 || (v.compareTo(max) == 0 && exclusive)) {
            max = v;
            maxExclusive = exclusive;
        }
    }

    private static Duration duration(Elements elements, AnnotationMirror m) {
        return Duration.ofDays(Mirrors.number(elements, m, "days"))
                .plusHours(Mirrors.number(elements, m, "hours"))
                .plusMinutes(Mirrors.number(elements, m, "minutes"))
                .plusSeconds(Mirrors.number(elements, m, "seconds"))
                .plusMillis(Mirrors.number(elements, m, "millis"))
                .plusNanos(Mirrors.number(elements, m, "nanos"));
    }
}
