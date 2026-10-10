package dev.docuconf.spring;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
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
 * with {@code reload = WATCH}, docuconf re-checks the files when they change and, if they still pass every check,
 * updates the value here, then calls the hooks registered with {@link #onChange(String, Class, Consumer)} and
 * publishes a {@link DocuconfFileReloadedEvent}. A change that fails a check is not used: the previous value stays,
 * and {@link #reloadStatus(String)} records the rejection.
 *
 * <pre>{@code
 * DocuconfFiles.Subscription s = files.onChange("rates", Rates.class, pricing::update);
 * ReloadStatus status = files.reloadStatus("rates");
 * }</pre>
 *
 * <p>A name that is not a declared input, or a hook or status on an input that is not watched, is a programming
 * error and throws {@link IllegalArgumentException} naming the inputs there are.
 */
public final class DocuconfFiles {

    private static final Log LOG = LogFactory.getLog(DocuconfFiles.class);

    /**
     * A registered on-change hook. {@link #close()} unregisters it; closing it again does nothing.
     */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        /** Unregisters the hook. */
        @Override
        void close();
    }

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Path> paths = new ConcurrentHashMap<>();
    private final Map<String, String> reloads = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, ReloadStatus> statuses = new ConcurrentHashMap<>();

    DocuconfFiles() {
    }

    void put(String input, Path path, Object value, String reload) {
        paths.put(input, path);
        reloads.put(input, reload == null ? "restart" : reload);
        if (value != null) {
            values.put(input, value);
        }
        statuses.put(input, new ReloadStatus(value == null ? 0 : 1, null, null));
    }

    void update(String input, Object value) {
        update(input, value, Instant.now());
    }

    /**
     * Swaps in a new value that passed every check, then calls the hooks. A hook that throws is logged by input
     * name and exception type only (its message could quote the content), and the other hooks still run.
     *
     * @return the input's new generation
     */
    long update(String input, Object value, Instant now) {
        values.put(input, value);
        ReloadStatus next = statuses.merge(input, new ReloadStatus(1, now, null),
                (old, ignored) -> new ReloadStatus(old.generation() + 1, now, null));
        for (Consumer<Object> l : listeners.getOrDefault(input, List.of())) {
            try {
                l.accept(value);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                hookFailed(input, e);
            }
        }
        return next.generation();
    }

    /** Records a change that failed its checks; the value is left as it was. */
    void rejected(String input, List<String> codes, Instant now) {
        RejectedReload r = new RejectedReload(now, input, codes);
        statuses.merge(input, new ReloadStatus(0, null, r),
                (old, ignored) -> new ReloadStatus(old.generation(), old.lastReload(), r));
    }

    static void hookFailed(String input, Throwable e) {
        LOG.warn("docuconf: an on-change hook or listener for " + input + " failed with " + e.getClass().getName()
                + "; the reload stands and the others still run");
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
     * Calls {@code listener} with the new value each time a watched input changes and passes its checks: after the
     * new value has replaced the old one, on docuconf's watcher thread, never for a change that failed a check.
     * Several hooks may be registered on one input; one that throws is logged, by input name and exception type
     * only, and neither stops the others nor undoes the reload.
     *
     * @param input the input name, declared with {@code reload = WATCH}
     * @param type the input's type, such as {@code Rates.class} or {@code TlsKeyPair.class}
     * @param listener receives the new value
     * @param <T> the type
     * @return a subscription whose {@link Subscription#close()} unregisters the hook
     * @throws IllegalArgumentException if no watched file input has this name, or its value is not a {@code type}
     */
    public <T> Subscription onChange(String input, Class<T> type, Consumer<? super T> listener) {
        watched(input);
        Object current = values.get(input);
        if (current != null && !type.isInstance(current)) {
            throw new IllegalArgumentException("docuconf: file input '" + input + "' is a "
                    + current.getClass().getName() + ", not a " + type.getName());
        }
        Consumer<Object> hook = v -> listener.accept(type.cast(v));
        List<Consumer<Object>> hooks = listeners.computeIfAbsent(input, k -> new CopyOnWriteArrayList<>());
        hooks.add(hook);
        return () -> hooks.remove(hook);
    }

    /**
     * Calls {@code listener} with the new value each time a watched input changes and passes its checks, as
     * {@link #onChange(String, Class, Consumer)} describes.
     *
     * @param input the input name, declared with {@code reload = WATCH}
     * @param listener receives the new value
     * @return a subscription whose {@link Subscription#close()} unregisters the hook
     * @throws IllegalArgumentException if no watched file input has this name
     */
    public Subscription onChange(String input, Consumer<Object> listener) {
        return onChange(input, Object.class, listener);
    }

    /**
     * The reload status of a watched input (SPEC §4.6.2): its generation, the time of the last accepted reload, and
     * the last rejected change, by time and violation codes, never content.
     *
     * @param input the input name, declared with {@code reload = WATCH}
     * @return the status at this moment
     * @throws IllegalArgumentException if no watched file input has this name
     */
    public ReloadStatus reloadStatus(String input) {
        watched(input);
        return statuses.get(input);
    }

    /**
     * The reload status of every watched input, by name, for a health endpoint or a metric.
     *
     * @return the statuses, sorted by input name; empty when no input is watched
     */
    public SortedMap<String, ReloadStatus> reloadStatuses() {
        SortedMap<String, ReloadStatus> out = new TreeMap<>();
        reloads.forEach((name, r) -> {
            if ("watch".equals(r)) {
                out.put(name, statuses.get(name));
            }
        });
        return Collections.unmodifiableSortedMap(out);
    }

    private void watched(String input) {
        declared(input);
        if (!"watch".equals(reloads.get(input))) {
            throw new IllegalArgumentException("docuconf: file input '" + input + "' is reloaded on restart, so"
                    + " it never changes while the app runs; declare it with reload = Reload.WATCH. Watched inputs: "
                    + names("watch"));
        }
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
