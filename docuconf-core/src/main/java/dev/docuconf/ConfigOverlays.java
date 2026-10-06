package dev.docuconf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Holds repeated {@link ConfigOverlay} annotations. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigOverlays {

    /**
     * The overlays.
     *
     * @return the overlays
     */
    ConfigOverlay[] value();
}
