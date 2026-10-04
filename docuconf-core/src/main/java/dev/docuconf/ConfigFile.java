package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A structured configuration file (JSON, YAML or TOML), deserialized with Jackson into the property's type and
 * validated with Bean Validation. The contract carries a JSON Schema generated from that type, so the platform
 * checks the file against the same type the app binds. Unknown properties are rejected, matching the schema's
 * {@code additionalProperties: false}. Mark the property {@code @NotNull} to make the file required.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface ConfigFile {

    /**
     * The absolute path of the file.
     *
     * @return the path
     */
    String value();

    /**
     * The file format. {@link Format#AUTO} picks it from the extension.
     *
     * @return the format
     */
    Format format() default Format.AUTO;

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

    /** Config file formats. */
    enum Format {
        /** From the extension: {@code .json}, {@code .yaml}/{@code .yml} or {@code .toml}. */
        AUTO,
        /** JSON. */
        JSON,
        /** YAML. */
        YAML,
        /** TOML. */
        TOML
    }
}
