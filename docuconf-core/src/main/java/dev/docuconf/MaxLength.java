package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The greatest length of a {@code url} or {@link Json @Json} variable, in characters (Unicode code points, not
 * UTF-16 units), exported as {@code maxLength} (SPEC §4.3). For a host that stores the value in a fixed-width
 * field. A url is measured as it is; a json value as the app receives it, whitespace included, before it is
 * parsed, or as compact JSON when it comes from {@code application*.yml} or an overlay as nested keys. A value
 * above it fails startup with {@code out_of_range}.
 *
 * <p>For a string, use Bean Validation's {@code @Size(max = n)}; for each item of a list of strings,
 * {@code List<@Size(max = n) String>}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface MaxLength {

    /**
     * The greatest length in characters.
     *
     * @return the length, at least 0
     */
    int value();
}
