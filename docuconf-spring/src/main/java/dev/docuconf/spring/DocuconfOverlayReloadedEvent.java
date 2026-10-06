package dev.docuconf.spring;

import org.springframework.context.ApplicationEvent;

/**
 * Published after a watched overlay changed, its new values passed every check, and the {@code @Docuconf}
 * JavaBeans were rebound. Listen for it to refresh anything derived from those properties.
 */
public class DocuconfOverlayReloadedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String overlay;

    /**
     * Creates the event.
     *
     * @param source the watcher
     * @param overlay the overlay name
     */
    public DocuconfOverlayReloadedEvent(Object source, String overlay) {
        super(source);
        this.overlay = overlay;
    }

    /**
     * The overlay that changed.
     *
     * @return its name in the contract, such as {@code platform}
     */
    public String getOverlay() {
        return overlay;
    }
}
