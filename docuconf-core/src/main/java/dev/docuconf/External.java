package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The property is supplied by a source the platform does not control (Vault, AWS Secrets Manager, Azure Key
 * Vault, Spring Cloud Config), so it is left out of the contract and not checked at startup.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface External {

    /**
     * The source that supplies the value, for documentation.
     *
     * @return the source
     */
    String value() default "";
}
