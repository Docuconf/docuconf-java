package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets how an enum property's values are spelled in the contract, overriding {@link Docuconf#enumCase()}.
 *
 * <pre>{@code
 * @EnumValues(EnumCase.LOWER) @DefaultValue("INFO") LogLevel logLevel   // values: debug, info, warn, error
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface EnumValues {

    /**
     * The spelling.
     *
     * @return the case
     */
    EnumCase value();
}
