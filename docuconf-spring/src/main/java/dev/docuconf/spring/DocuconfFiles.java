package dev.docuconf.spring;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
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
 * value here and calls the listeners registered with {@link #onChange(String, Class, Consumer)}.
 *
 * <pre>{@code
 * files.onChange("rates", Rates.class, pricing::update);
 * }</pre>
 *
 * <p>A name that is not a declared input, or a listener on an input that is not watched, is a programming error
 * and throws {@link IllegalArgumentException} naming the inputs there are.
 */
public final class DocuconfFiles {

    private static final Log LOG = LogFactory.getLog(DocuconfFiles.class);

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Path> paths = new ConcurrentHashMap<>();
    private final Map<String, String> reloads = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    DocuconfFiles() {
    }

    void put(String input, Path path, Object value, String reload) {
        paths.put(input, path);
        reloads.put(input, reload == null ? "restart" : reload);
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

    /** For binding: the value, or {@code null}, without the checks the public API makes. */
    Object value(String input) {
        return values.get(input);
    }

    /**
     * The current value of a file input.
     *
     * @param input the input name
     * @param type the expected type
     * @param <T> the type
     * @return the value, or empty when the (optional) file is absent
     * @throws IllegalArgumentException if no file input has this name
     * @throws ClassCastException if the value is not a {@code type}
     */
    public <T> Optional<T> get(String input, Class<T> type) {
        declared(input);
        return Optional.ofNullable(values.get(input)).map(type::cast);
    }

    /**
     * Where a file input was read from, after {@code pathEnv} and {@code DOCUCONF_FILE_ROOT}.
     *
     * @param input the input name
     * @return the path
     * @throws IllegalArgumentException if no file input has this name
     */
    public Optional<Path> path(String input) {
        declared(input);
        return Optional.ofNullable(paths.get(input));
    }

    /**
     * Calls {@code listener} with the new value each time a watched input changes and passes its checks.
     *
     * @param input the input name, declared with {@code reload = WATCH}
     * @param type the input's type, such as {@code Rates.class} or {@code TlsKeyPair.class}
     * @param listener receives the new value
     * @param <T> the type
     * @throws IllegalArgumentException if no watched file input has this name, or its value is not a {@code type}
     */
    public <T> void onChange(String input, Class<T> type, Consumer<? super T> listener) {
        declared(input);
        if (!"watch".equals(reloads.get(input))) {
            throw new IllegalArgumentException("docuconf: file input '" + input + "' is reloaded on restart, so"
                    + " it never changes while the app runs; declare it with reload = Reload.WATCH. Watched inputs: "
                    + names("watch"));
        }
        Object current = values.get(input);
        if (current != null && !type.isInstance(current)) {
            throw new IllegalArgumentException("docuconf: file input '" + input + "' is a "
                    + current.getClass().getName() + ", not a " + type.getName());
        }
        listeners.computeIfAbsent(input, k -> new CopyOnWriteArrayList<>()).add(v -> listener.accept(type.cast(v)));
    }

    /**
     * Calls {@code listener} with the new value each time a watched input changes and passes its checks.
     *
     * @param input the input name, declared with {@code reload = WATCH}
     * @param listener receives the new value
     * @throws IllegalArgumentException if no watched file input has this name
     */
    public void onChange(String input, Consumer<Object> listener) {
        onChange(input, Object.class, listener);
    }

    private void declared(String input) {
        if (!reloads.containsKey(input)) {
            throw new IllegalArgumentException("docuconf: no file input '" + input + "'; inputs: " + names(null));
        }
    }

    private String names(String reload) {
        TreeSet<String> names = new TreeSet<>();
        reloads.forEach((name, r) -> {
            if (reload == null || reload.equals(r)) {
                names.add(name);
            }
        });
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
