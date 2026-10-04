package dev.docuconf.spring;

import dev.docuconf.check.Violation;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Implements {@code reload: watch} (SPEC §11.2 item 8). Kubernetes updates a projected volume by swapping a
 * {@code ..data} symlink in the mount directory, so the watcher watches each input's mount directory (the TLS
 * directory, or a file's parent), re-checks the input after any change there, and publishes the new value through
 * {@link DocuconfFiles} only if it passes every check. An invalid update is logged and the last good value kept.
 */
public class DocuconfFileWatcher implements SmartLifecycle {

    private static final Log LOG = LogFactory.getLog(DocuconfFileWatcher.class);

    private final DocuconfChecker checker;
    private final long settleMillis;
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
                        reload(f);
                    }
                }
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
        }
    }

    private void reload(FileSpec f) {
        List<Violation> violations = new ArrayList<>();
        Object value = checker.recheck(f.name, violations);
        if (!violations.isEmpty()) {
            LOG.warn("docuconf: " + f.name + " changed but is invalid; keeping the previous version: " + violations);
            return;
        }
        if (value != null) {
            LOG.info("docuconf: reloaded " + f.name);
            checker.files().update(f.name, value);
        }
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
