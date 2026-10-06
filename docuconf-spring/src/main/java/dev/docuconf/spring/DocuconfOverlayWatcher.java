package dev.docuconf.spring;

import dev.docuconf.check.Violation;
import dev.docuconf.contract.Bindings;
import dev.docuconf.contract.ContractBundle;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Implements {@code reload: watch} for config-file overlays (SPEC §4.7, §11.2 item 9).
 *
 * <p>Spring Boot has no reload of its own for {@code @ConfigurationProperties} (Spring Cloud's refresh scope is a
 * separate project), so docuconf does it for the classes in its contract:
 *
 * <ol>
 *   <li>It polls each watched overlay's content, and acts once a change has held for one interval. Kubernetes updates a mounted ConfigMap by swapping a
 *       {@code ..data} symlink, which polling sees regardless of how the swap is done, and a directory that appears
 *       later (an overlay first supplied after startup) needs no special handling.</li>
 *   <li>On a change it reads the file and checks every variable against the environment as it would be with the
 *       new overlay, exactly as at startup. If anything fails, the change is logged and ignored: the app keeps the
 *       last good values.</li>
 *   <li>Otherwise it swaps the overlay's property source, binds a fresh instance of each {@code @Docuconf}
 *       JavaBean and copies its properties into the live bean (so a key removed from the overlay falls back to its
 *       default), then publishes a {@link DocuconfOverlayReloadedEvent}.</li>
 * </ol>
 *
 * <p>Read values from the properties bean when you need them, not once at startup. A reader on another thread may
 * see a mix of old and new values while the copy runs. Classes bound through their constructor (records) cannot
 * be rebound; the annotation processor refuses {@code WATCH} when one of them holds a variable an overlay could
 * carry.
 */
public class DocuconfOverlayWatcher implements SmartLifecycle {

    private static final Log LOG = LogFactory.getLog(DocuconfOverlayWatcher.class);

    private final DocuconfChecker checker;
    private final ConfigurableEnvironment environment;
    private final ApplicationContext context;
    private final Duration interval;
    private final Map<String, byte[]> seen = new HashMap<>();
    private final Map<String, byte[]> pending = new HashMap<>();
    private Thread thread;
    private volatile boolean running;

    /**
     * Creates a watcher.
     *
     * @param checker the checker that ran at startup, or {@code null} when none did
     * @param environment the application environment
     * @param context the application context, whose {@code @Docuconf} beans are rebound
     * @param interval how often to look at the overlay files
     */
    public DocuconfOverlayWatcher(DocuconfChecker checker, ConfigurableEnvironment environment,
            ApplicationContext context, Duration interval) {
        this.checker = checker;
        this.environment = environment;
        this.context = context;
        this.interval = interval;
    }

    @Override
    public void start() {
        if (checker == null) {
            return;
        }
        List<String> watched = new ArrayList<>();
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps instanceof DocuconfOverlaySource o && "watch".equals(o.spec().reload)) {
                watched.add(o.getName());
                seen.put(o.getName(), o.digest());
            }
        }
        if (watched.isEmpty()) {
            return;
        }
        running = true;
        thread = new Thread(() -> loop(watched), "docuconf-overlay-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(List<String> watched) {
        while (running) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                return;
            }
            for (String name : watched) {
                if (environment.getPropertySources().get(name) instanceof DocuconfOverlaySource current) {
                    byte[] now = DocuconfOverlaySource.digestOf(current.path());
                    if (Arrays.equals(now, seen.get(name))) {
                        pending.remove(name);
                        continue;
                    }
                    // Act once the content has stayed the same for a whole interval, so a file written in
                    // place (not swapped, as Kubernetes does) is not read half-written.
                    if (!pending.containsKey(name) || !Arrays.equals(now, pending.get(name))) {
                        pending.put(name, now);
                        continue;
                    }
                    pending.remove(name);
                    seen.put(name, now);
                    try {
                        reload(current);
                    } catch (RuntimeException e) {
                        LOG.warn("docuconf: reloading overlay " + current.spec().name + " failed; keeping the"
                                + " previous values", e);
                    }
                }
            }
        }
    }

    /**
     * Re-reads one overlay and applies it if it passes every check.
     *
     * @param current the overlay's live property source
     * @return whether the new values were applied
     */
    synchronized boolean reload(DocuconfOverlaySource current) {
        String overlay = current.spec().name;
        DocuconfOverlaySource next = DocuconfOverlaySource.load(current.spec(), current.path());
        if (next.error() != null) {
            LOG.warn("docuconf: overlay " + overlay + " changed but " + next.error() + "; keeping the previous values");
            return false;
        }
        List<Violation> violations = checker.recheckVars(candidate(current.getName(), next));
        if (!violations.isEmpty()) {
            LOG.warn("docuconf: overlay " + overlay + " changed but is invalid; keeping the previous values: "
                    + violations);
            return false;
        }
        environment.getPropertySources().replace(current.getName(), next);
        rebind();
        LOG.info("docuconf: reloaded overlay " + overlay);
        context.publishEvent(new DocuconfOverlayReloadedEvent(this, overlay));
        return true;
    }

    /** The live environment with one overlay replaced, for checking before anything changes. */
    private ConfigurableEnvironment candidate(String name, PropertySource<?> replacement) {
        StandardEnvironment candidate = new StandardEnvironment();
        candidate.setConversionService(environment.getConversionService());
        MutablePropertySources sources = candidate.getPropertySources();
        sources.stream().map(PropertySource::getName).toList().forEach(sources::remove);
        for (PropertySource<?> ps : environment.getPropertySources()) {
            // The attached configurationProperties source views the live environment; leave it out.
            if (!ps.getName().equals("configurationProperties")) {
                sources.addLast(ps.getName().equals(name) ? replacement : ps);
            }
        }
        ConfigurationPropertySources.attach(candidate);
        return candidate;
    }

    private void rebind() {
        ClassLoader classLoader = context.getClassLoader();
        for (ContractBundle bundle : checker.bundles()) {
            for (Bindings.ClassBinding cb : bundle.bindings().classes) {
                Class<?> type;
                try {
                    type = ClassUtils.forName(cb.className(), classLoader);
                } catch (ClassNotFoundException | LinkageError e) {
                    continue;
                }
                if (type.isRecord() || Modifier.isAbstract(type.getModifiers()) || !hasNoArgConstructor(type)) {
                    continue; // Constructor-bound: immutable.
                }
                for (Object live : context.getBeanProvider(type).stream().toList()) {
                    Object fresh = BeanUtils.instantiateClass(type);
                    checker.binder().bind(cb.prefix(), Bindable.ofInstance(fresh));
                    copy(fresh, live, 0);
                }
            }
        }
    }

    private static boolean hasNoArgConstructor(Class<?> type) {
        try {
            type.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Copies bound properties into the live bean: setters where there are any, else into the nested object. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void copy(Object from, Object to, int depth) {
        BeanWrapper source = new BeanWrapperImpl(from);
        BeanWrapper target = new BeanWrapperImpl(to);
        for (PropertyDescriptor pd : source.getPropertyDescriptors()) {
            String name = pd.getName();
            if (name.equals("class") || !source.isReadableProperty(name) || !target.isReadableProperty(name)) {
                continue;
            }
            Object value = source.getPropertyValue(name);
            if (target.isWritableProperty(name)) {
                target.setPropertyValue(name, value);
                continue;
            }
            Object current = target.getPropertyValue(name);
            if (current == null || value == null || current == value) {
                continue;
            }
            try {
                if (current instanceof Collection c && value instanceof Collection v) {
                    c.clear();
                    c.addAll(v);
                } else if (current instanceof Map m && value instanceof Map v) {
                    m.clear();
                    m.putAll(v);
                } else if (current.getClass() == value.getClass() && depth < 8
                        && !BeanUtils.isSimpleValueType(current.getClass())) {
                    copy(value, current, depth + 1);
                }
            } catch (UnsupportedOperationException e) {
                // An immutable collection behind a getter: Spring could not have bound it either.
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
