package dev.docuconf.spring.fixture;

import dev.docuconf.spring.DocuconfFileReloadedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Records reload events, as an app's {@code @EventListener} would receive them. */
@Component
public class ReloadRecorder {

    /** The events seen, in order. */
    public final List<DocuconfFileReloadedEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Fails, as a careless listener might; the listener after it must still run.
     *
     * @param event the event
     */
    @EventListener
    @Order(1)
    public void failing(DocuconfFileReloadedEvent event) {
        throw new IllegalStateException("listener failed");
    }

    /**
     * Records the event.
     *
     * @param event the event
     */
    @EventListener
    @Order(2)
    public void record(DocuconfFileReloadedEvent event) {
        events.add(event);
    }
}
