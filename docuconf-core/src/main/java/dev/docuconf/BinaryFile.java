package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opaque bytes, such as a GeoIP database. The property must be a {@link java.nio.file.Path}, set to the resolved
 * location of the file.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface BinaryFile {

    /**
     * The absolute path of the file.
     *
     * @return the path
     */
    String value();

    /**
     * The input name in the contract, a DNS label. Defaults to the property name in kebab-case.
     *
     * @return the input name
     */
    String name() default "";

    /**
     * An environment variable the platform sets to the path, for apps that read the location from the
     * environment. When it is set at startup, docuconf reads the file from there.
     *
     * @return the variable name, or empty
     */
    String pathEnv() default "";

    /**
     * How the app picks up a changed file. {@link Reload#WATCH} starts a watcher that re-checks the file and
     * notifies {@code DocuconfFiles} listeners.
     *
     * @return the reload mode
     */
    Reload reload() default Reload.RESTART;

    /**
     * Upper bound on the file size in bytes; zero means no limit.
     *
     * @return the size limit
     */
    long maxSize() default 0;
}
