package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a configuration property or file input for the contract and its generated docs. Without it, the
 * processor uses the Javadoc of the field, record component ({@code @param}) or getter. Every input needs a
 * description of at least five characters.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Description {

    /**
     * The description.
     *
     * @return the description
     */
    String value();
}
