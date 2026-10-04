package dev.docuconf.spring;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The file inputs docuconf checked at startup, by input name, with their current values: a
 * {@link dev.docuconf.TlsKeyPair}, {@link dev.docuconf.CaBundle}, {@link dev.docuconf.Keystore}, the text of a text
 * file, the {@link Path} of a binary file, or the object a config file was bound to.
 *
 * <p>{@code @ConfigurationProperties} beans receive these values once, when they are bound. For inputs declared
 * with {@code reload = WATCH}, docuconf re-checks the file when it changes and, if it is still valid, updates the
 * value here and calls the listeners registered with {@link #onChange(String, Consumer)}.
 */
public final class DocuconfFiles {

    private static final Log LOG = LogFactory.getLog(DocuconfFiles.class);

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Path> paths = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    DocuconfFiles() {
    }

    void put(String input, Path path, Object value) {
        paths.put(input, path);
        if (value != null) {
            values.put(input, value);
        }
    }

    void update(String input, Object value) {
        values.put(input, value);
        for (Consumer<Object> l : listeners.getOrDefault(input, List.of())) {
            try {
                l.accept(value);
            } catch (RuntimeException e) {
                LOG.warn("docuconf: a listener for " + input + " failed", e);
            }
        }
    }

    /**
     * The current value of a file input.
     *
     * @param input the input name
     * @param type the expected type
     * @param <T> the type
     * @return the value, or empty when the (optional) file is absent
     */
    public <T> Optional<T> get(String input, Class<T> type) {
        return Optional.ofNullable(values.get(input)).map(type::cast);
    }

    /**
     * Where a file input was read from, after {@code pathEnv} and {@code DOCUCONF_FILE_ROOT}.
     *
     * @param input the input name
     * @return the path, or empty for an undeclared input
     */
    public Optional<Path> path(String input) {
        return Optional.ofNullable(paths.get(input));
    }

    /**
     * Calls {@code listener} with the new value each time a watched input changes and passes its checks.
     *
     * @param input the input name
     * @param listener receives the new value
     */
    public void onChange(String input, Consumer<Object> listener) {
        listeners.computeIfAbsent(input, k -> new CopyOnWriteArrayList<>()).add(listener);
    }
}
