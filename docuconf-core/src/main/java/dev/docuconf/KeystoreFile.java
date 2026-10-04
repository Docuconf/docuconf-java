package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A PKCS#12 or JKS keystore. The property must be a {@link Keystore}. Always secret. Its password is another
 * property of the same class, marked {@link Secret}, named by {@link #passwordProperty()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface KeystoreFile {

    /**
     * The absolute path of the keystore.
     *
     * @return the path
     */
    String value();

    /**
     * The keystore format.
     *
     * @return the format
     */
    Format format() default Format.PKCS12;

    /**
     * The Java name of the {@link Secret} property in the same class that holds the password; empty for a
     * keystore without a password.
     *
     * @return the property name
     */
    String passwordProperty() default "";

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

    /** Keystore formats. */
    enum Format {
        /** PKCS#12. */
        PKCS12,
        /** The legacy Java KeyStore format. */
        JKS
    }
}
