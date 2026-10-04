package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value is a URL ({@code scheme://...}) whose scheme must be one of {@link #value()}. Applies to
 * {@code String}, {@code URI} and {@code URL} properties; a {@code URI} or {@code URL} without this annotation is
 * still a {@code url} in the contract, with any scheme.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface UrlSchemes {

    /**
     * The allowed schemes, such as {@code https} or {@code postgres}.
     *
     * @return the schemes
     */
    String[] value();
}
