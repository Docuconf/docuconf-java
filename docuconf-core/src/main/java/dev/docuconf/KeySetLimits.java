package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bounds a {@link KeySet} property (SPEC §4.3): how many keys it holds, and how long each key is, in characters
 * (Unicode code points). Without it a key set holds 1 or 2 keys of any non-empty length. A number of keys outside
 * the bounds fails startup with {@code too_few_items} or {@code too_many_items}; a key outside the lengths, or an
 * empty key, with {@code out_of_range}. No message holds a key.
 *
 * <pre>{@code
 * @KeySetLimits(keyMinLength = 32, keyMaxLength = 256) KeySet keys
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface KeySetLimits {

    /**
     * Least number of keys, at least 1.
     *
     * @return the bound
     */
    int minKeys() default 1;

    /**
     * Greatest number of keys, at least {@link #minKeys()}. Two lets one key be rotated: old and new.
     *
     * @return the bound
     */
    int maxKeys() default 2;

    /**
     * Least length of each key in characters; 0 means no bound beyond non-empty.
     *
     * @return the bound
     */
    int keyMinLength() default 0;

    /**
     * Greatest length of each key in characters; 0 means no bound.
     *
     * @return the bound
     */
    int keyMaxLength() default 0;
}
