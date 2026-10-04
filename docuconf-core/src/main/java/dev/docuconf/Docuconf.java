package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @ConfigurationProperties} class as part of the service's docuconf contract.
 *
 * <p>The docuconf annotation processor exports every marked class in a compilation into one contract,
 * {@code META-INF/docuconf/contract.cue}, and the Spring Boot auto-configuration validates each of them at
 * startup, before any {@code @ConfigurationProperties} bean is bound.
 *
 * <pre>{@code
 * @Docuconf
 * @Validated
 * @ConfigurationProperties("billing")
 * public record BillingProperties(...) {}
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Docuconf {

    /**
     * The service name, a DNS label such as {@code billing-api}. When empty, the processor uses
     * {@code spring.application.name} from {@code application.yml}, then the {@code docuconf.name} processor
     * option. Every marked class in a compilation must agree.
     *
     * @return the service name
     */
    String service() default "";
}
