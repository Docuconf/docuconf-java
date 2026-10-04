package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A TLS key pair in the {@code kubernetes.io/tls} layout: a directory holding {@code tls.crt}, {@code tls.key} and,
 * with {@link #requireCA()}, {@code ca.crt}. The property must be a {@link TlsKeyPair}. Always secret. Mark the
 * property {@code @NotNull} to make it required.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface TlsFile {

    /**
     * The absolute path of the directory.
     *
     * @return the directory
     */
    String value();

    /**
     * Names the certificate must cover. A wildcard certificate covers one label.
     *
     * @return the DNS names
     */
    String[] dnsNames() default {};

    /**
     * Allowed key algorithms; empty allows any.
     *
     * @return the algorithms
     */
    KeyAlgorithm[] keyAlgorithms() default {};

    /**
     * Least remaining validity, as a Go duration such as {@code 720h}.
     *
     * @return the duration, or empty
     */
    String minRemaining() default "";

    /**
     * Whether {@code ca.crt} must be present and the certificate must chain to it.
     *
     * @return whether a CA is required
     */
    boolean requireCA() default false;

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
