package dev.docuconf.spring;

import dev.docuconf.check.Violation;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * Implements {@code reload: watch} (SPEC §4.6.2, §11.2 item 8). Kubernetes updates a projected volume by swapping a
 * {@code ..data} symlink in the mount directory, so the watcher watches each input's mount directory (the TLS
 * directory, or a file's parent) with a {@link WatchService}, in the background. After an event there it compares a
 * hash of the input's files (read through any symlink) with the content it last checked, and re-checks the input
 * only if they differ. A change that passes every check replaces the value in {@link DocuconfFiles}, which calls the
 * on-change hooks, and is then published as a {@link DocuconfFileReloadedEvent}. A change that fails is logged by
 * violation, recorded in {@link DocuconfFiles#reloadStatus(String)}, and not used: the last good value stays.
 *
 * <p>Hooks and events fire from this thread, without the app reading the value. A keystore is re-opened with the
 * password read at startup.
 */
public class DocuconfFileWatcher implements SmartLifecycle, ApplicationContextAware {

    private static final Log LOG = LogFactory.getLog(DocuconfFileWatcher.class);

    private final DocuconfChecker checker;
    private final long settleMillis;
    private final Map<String, byte[]> seen = new HashMap<>();
    private ApplicationContext context;
    private WatchService service;
    private Thread thread;
    private volatile boolean running;

    /**
     * Creates a watcher.
     *
     * @param checker the checker that ran at startup, or {@code null} when none did
     * @param settleMillis how long to wait after a change before re-checking, so a multi-file update lands first
     */
    public DocuconfFileWatcher(DocuconfChecker checker, long settleMillis) {
        this.checker = checker;
        this.settleMillis = settleMillis;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void start() {
        if (checker == null) {
            return;
        }
        List<FileSpec> watched = checker.watchedFiles();
        if (watched.isEmpty()) {
            return;
        }
        Map<WatchKey, List<FileSpec>> byKey = new HashMap<>();
        try {
            service = FileSystems.getDefault().newWatchService();
            for (FileSpec f : watched) {
                Path path = checker.files().path(f.name).orElse(null);
                if (path == null) {
                    continue;
                }
                seen.put(f.name, digest(f, path));
                Path dir = f.type == FileType.TLS ? path : path.toAbsolutePath().getParent();
                if (dir == null || !dir.toFile().isDirectory()) {
                    LOG.warn("docuconf: cannot watch " + f.name + ": " + dir + " does not exist");
                    continue;
                }
                WatchKey key = dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            }
        } catch (IOException e) {
            LOG.warn("docuconf: cannot watch file inputs", e);
            return;
        }
        running = true;
        thread = new Thread(() -> loop(byKey), "docuconf-file-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(Map<WatchKey, List<FileSpec>> byKey) {
        while (running) {
            WatchKey key;
            try {
                key = service.take();
                // Let the rest of an update (several files, a symlink swap) arrive, then coalesce.
                Thread.sleep(settleMillis);
                List<WatchKey> keys = new ArrayList<>(List.of(key));
                WatchKey more;
                while ((more = service.poll(0, TimeUnit.MILLISECONDS)) != null) {
                    keys.add(more);
                }
                for (WatchKey k : keys) {
                    k.pollEvents();
                    k.reset();
                    for (FileSpec f : byKey.getOrDefault(k, List.of())) {
                        try {
                            reload(f);
                        } catch (RuntimeException e) {
                            LOG.warn("docuconf: reloading " + f.name + " failed with " + e.getClass().getName()
                                    + "; keeping the previous version");
                        }
                    }
                }
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
        }
    }

    /**
     * Re-checks one input if its content changed since it was last checked.
     *
     * @param f the input
     * @return whether a new value was accepted
     */
    synchronized boolean reload(FileSpec f) {
        Path path = checker.files().path(f.name).orElse(null);
        if (path == null) {
            return false;
        }
        byte[] now = digest(f, path);
        if (Arrays.equals(now, seen.get(f.name))) {
            return false; // An event for another file in the directory, or a touch.
        }
        seen.put(f.name, now);
        List<Violation> violations = new ArrayList<>();
        Object value = checker.recheck(f.name, violations);
        if (!violations.isEmpty()) {
            LOG.warn("docuconf: " + f.name + " changed but is invalid; keeping the previous version: " + violations);
            checker.files().rejected(f.name, violations.stream().map(v -> v.code().id()).distinct().toList(),
                    Instant.now());
            return false;
        }
        if (value == null) {
            return false; // An optional input that is absent: there is nothing to swap in.
        }
        LOG.info("docuconf: reloading " + f.name);
        long generation = checker.files().update(f.name, value, Instant.now());
        publish(new DocuconfFileReloadedEvent(this, f.name, value, generation));
        LOG.info("docuconf: reloaded " + f.name + " (generation " + generation + ")");
        return true;
    }

    /**
     * Publishes the event to the context's listeners one by one, so that a listener that throws is logged and the
     * others still run. Spring's own multicaster stops at the first listener that throws unless the app gave it an
     * error handler, so the listeners the context holds ({@code @EventListener} methods, singleton
     * {@code ApplicationListener} beans and listeners added to the application) are handed to a multicaster of
     * docuconf's own that has one.
     */
    private void publish(DocuconfFileReloadedEvent event) {
        if (context == null) {
            return;
        }
        String input = event.getInput();
        if (context instanceof AbstractApplicationContext ac) {
            SimpleApplicationEventMulticaster m = new SimpleApplicationEventMulticaster();
            m.setErrorHandler(e -> DocuconfFiles.hookFailed(input, e));
            for (ApplicationListener<?> l : ac.getApplicationListeners()) {
                m.addApplicationListener(l);
            }
            m.multicastEvent(event);
            return;
        }
        try {
            context.publishEvent(event);
        } catch (RuntimeException e) {
            DocuconfFiles.hookFailed(input, e);
        }
    }

    /** A hash of what an input reads: tls.crt, tls.key and ca.crt for a TLS directory, else the file. */
    static byte[] digest(FileSpec f, Path path) {
        List<Path> parts = f.type == FileType.TLS
                ? List.of(path.resolve("tls.crt"), path.resolve("tls.key"), path.resolve("ca.crt"))
                : List.of(path);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (Path p : parts) {
            try {
                byte[] content = Files.readAllBytes(p);
                md.update((byte) 1);
                md.update(ByteBuffer.allocate(8).putLong(content.length).array());
                md.update(content);
            } catch (IOException e) {
                md.update((byte) 0); // Absent or unreadable: the check reports which.
            }
        }
        return md.digest();
    }

    @Override
    public void stop() {
        running = false;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                // Ignore.
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
