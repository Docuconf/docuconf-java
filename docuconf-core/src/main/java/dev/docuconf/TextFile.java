package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A text file, such as a licence key. The property must be a {@code String} and receives the content, unchanged
 * (a trailing newline is kept).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface TextFile {

    /**
     * The absolute path of the file.
     *
     * @return the path
     */
    String value();

    /**
     * An RE2 pattern the content must contain a match for. Unlike {@code @Pattern}, it matches anywhere: anchor
     * it with {@code ^} and {@code $}, where {@code $} means the very end of the content.
     *
     * @return the pattern, or empty
     */
    String pattern() default "";

    /**
     * Least length in characters.
     *
     * @return the minimum
     */
    int minLength() default 0;

    /**
     * Greatest length in characters; -1 means no limit.
     *
     * @return the maximum
     */
    int maxLength() default -1;

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
