package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a config-file overlay (SPEC §4.7): one more {@code application.yml}-style file that the platform mounts,
 * layered above the {@code application*.yml} files the app ships with and below environment variables:
 *
 * <pre>application.yml &lt; application-{profile}.yml &lt; overlay &lt; environment variables</pre>
 *
 * <p>Put it on a {@code @Docuconf} class. The processor exports it into the contract's {@code overlays} (format
 * {@code yaml}, key separator {@code .}), and docuconf-spring loads the file at startup, before the checks run, so
 * overlay values are validated like any other. A missing file is not an error.
 *
 * <pre>{@code
 * @Docuconf
 * @ConfigOverlay(value = "/etc/orders/overlay/orders.yaml", reload = Reload.WATCH)
 * @ConfigurationProperties("orders")
 * public class OrdersProperties { ... }
 * }</pre>
 *
 * <p>The overlay's directory is mounted, hiding whatever the image has there, so give it a directory of its own,
 * never one holding the app's files.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ConfigOverlays.class)
public @interface ConfigOverlay {

    /**
     * The absolute path of the file, ending in {@code .yml} or {@code .yaml}.
     *
     * @return the path
     */
    String value();

    /**
     * The overlay's name in the contract, a DNS label.
     *
     * @return the name
     */
    String name() default "platform";

    /**
     * What the overlay is for, at least five characters; empty for none.
     *
     * @return the description
     */
    String description() default "";

    /**
     * {@link Reload#WATCH} re-reads the file when the platform updates it and rebinds the {@code @Docuconf}
     * JavaBeans in place, after the new values pass every check. Constructor-bound classes (records) cannot be
     * rebound, so {@code WATCH} is refused when one of them has a variable an overlay could carry.
     * {@link Reload#RESTART} (the default) reads it once; a change rolls the pods.
     *
     * @return how a change is picked up
     */
    Reload reload() default Reload.RESTART;
}
