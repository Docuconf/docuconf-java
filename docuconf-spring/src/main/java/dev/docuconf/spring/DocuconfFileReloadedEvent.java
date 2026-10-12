package dev.docuconf.spring;

import org.springframework.context.ApplicationEvent;

/**
 * Published after a file input declared with {@code reload = WATCH} changed, its new content passed every check it
 * passed at startup, and the new value replaced the old one in {@link DocuconfFiles}. Never published for a
 * rejected change. Listen for it with {@code @EventListener} to rebuild anything made from the old value, such as an
 * {@code SSLContext} or an HTTP client:
 *
 * <pre>{@code
 * @EventListener(condition = "#event.input == 'tls'")
 * void tlsRenewed(DocuconfFileReloadedEvent event) {
 *     server.reload(((TlsKeyPair) event.getValue()).sslContext());
 * }
 * }</pre>
 *
 * <p>Each listener runs on docuconf's watcher thread. A listener that throws is logged, by input name and exception
 * type only, and neither stops the other listeners nor undoes the reload.
 */
public class DocuconfFileReloadedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String input;
    private final transient Object value;
    private final long generation;

    /**
     * Creates the event.
     *
     * @param source the watcher
     * @param input the input name
     * @param value the new value
     * @param generation the input's generation after this reload
     */
    public DocuconfFileReloadedEvent(Object source, String input, Object value, long generation) {
        super(source);
        this.input = input;
        this.value = value;
        this.generation = generation;
    }

    /**
     * The input that changed.
     *
     * @return its name in the contract, such as {@code tls}
     */
    public String getInput() {
        return input;
    }

    /**
     * The new value, as {@link DocuconfFiles#get(String, Class)} returns it: a {@link dev.docuconf.TlsKeyPair},
     * {@link dev.docuconf.CaBundle}, {@link dev.docuconf.Keystore}, the text of a text file, the {@code Path} of a
     * binary file, or the object a config file was bound to.
     *
     * @return the value
     */
    public Object getValue() {
        return value;
    }

    /**
     * The new value as a given type.
     *
     * @param type the expected type
     * @param <T> the type
     * @return the value
     * @throws ClassCastException if it is not a {@code type}
     */
    public <T> T getValue(Class<T> type) {
        return type.cast(value);
    }

    /**
     * The input's generation after this reload, as {@link ReloadStatus#generation()} counts it.
     *
     * @return 2 after the first accepted reload, and so on
     */
    public long getGeneration() {
        return generation;
    }
}
