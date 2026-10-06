package dev.docuconf.spring;

import dev.docuconf.check.FileChecker;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.OverlaySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Loads the config-file overlays declared with {@code @ConfigOverlay} (SPEC §4.7) into the environment, right
 * after Spring Boot has loaded its config data, at the position the spec fixes:
 *
 * <pre>application.yml &lt; application-{profile}.yml &lt; overlays &lt; environment variables</pre>
 *
 * <p>Each overlay becomes a property source just below {@code systemEnvironment}, so it beats every
 * {@code application*.yml} (and {@code spring.config.import}ed file) and loses to environment variables, system
 * properties and command-line arguments. A missing file is an empty source. {@code DOCUCONF_FILE_ROOT} prefixes
 * the path, as it does for file inputs.
 *
 * <p>The overlay is not Spring config data: it cannot activate profiles or import further files.
 */
public class DocuconfOverlayEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Runs just after {@link ConfigDataEnvironmentPostProcessor}. */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    /** Creates the post-processor. */
    public DocuconfOverlayEnvironmentPostProcessor() {
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassLoader classLoader = application.getClassLoader();
        List<ContractBundle> bundles;
        try {
            bundles = DocuconfChecker.load(classLoader != null ? classLoader
                    : DocuconfOverlayEnvironmentPostProcessor.class.getClassLoader());
        } catch (IOException e) {
            throw new UncheckedIOException("docuconf: cannot read META-INF/docuconf/contract.json", e);
        }
        Map<String, OverlaySpec> overlays = new LinkedHashMap<>();
        for (ContractBundle b : bundles) {
            b.contract().overlays.values().forEach(o -> overlays.putIfAbsent(o.name, o));
        }
        if (overlays.isEmpty()) {
            return;
        }
        List<Path> appDirs = appDirectories(application);
        String previous = null;
        for (OverlaySpec o : overlays.values()) {
            Path path = resolve(o, environment);
            refuseAppDirectory(o, path, appDirs);
            previous = place(environment.getPropertySources(), DocuconfOverlaySource.load(o, path), previous);
        }
    }

    /**
     * Where an overlay is read from: its path, under {@code DOCUCONF_FILE_ROOT} when that is set.
     *
     * @param o the overlay
     * @param environment the environment
     * @return the path
     */
    static Path resolve(OverlaySpec o, ConfigurableEnvironment environment) {
        String root = environment.getProperty(FileChecker.FILE_ROOT_ENV);
        if (root != null && !root.isEmpty() && o.path.startsWith("/")) {
            return Path.of(root, o.path.substring(1));
        }
        return Path.of(o.path);
    }

    /**
     * Puts an overlay just below the environment variables (after any overlay placed before it, so the first
     * declared wins among overlays; a variable belongs to one overlay anyway).
     *
     * @return the name of the source placed
     */
    static String place(MutablePropertySources sources, PropertySource<?> overlay, String previous) {
        sources.remove(overlay.getName());
        if (previous != null && sources.contains(previous)) {
            sources.addAfter(previous, overlay);
        } else if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, overlay);
        } else {
            // No environment variables source: go above the first config data file.
            String firstConfig = sources.stream().map(PropertySource::getName)
                    .filter(n -> n.startsWith("Config resource")).findFirst().orElse(null);
            if (firstConfig != null) {
                sources.addBefore(firstConfig, overlay);
            } else {
                sources.addLast(overlay);
            }
        }
        return overlay.getName();
    }

    /** The jar's directory (or class output) and the working directory: where the app's own files are. */
    private static List<Path> appDirectories(SpringApplication application) {
        List<Path> dirs = new ArrayList<>();
        try {
            dirs.add(new ApplicationHome(application.getMainApplicationClass()).getDir().toPath());
        } catch (RuntimeException e) {
            // No home directory to protect.
        }
        dirs.add(Path.of(System.getProperty("user.dir", ".")));
        return dirs;
    }

    /**
     * SPEC §4.7: the platform mounts the overlay's directory, hiding whatever the image has there, so the
     * directory must not be the app's own.
     *
     * @param o the overlay
     * @param path where it is read from
     * @param appDirs the app's directories
     * @throws IllegalStateException when the overlay is in one of them
     */
    static void refuseAppDirectory(OverlaySpec o, Path path, List<Path> appDirs) {
        Path dir = path.toAbsolutePath().normalize().getParent();
        for (Path app : appDirs) {
            if (app != null && dir != null && dir.equals(app.toAbsolutePath().normalize())) {
                throw new IllegalStateException("docuconf: overlay " + o.name + " at " + o.path
                        + " is in the app's own directory (" + app + "); mounting it would hide the app's files."
                        + " Give the overlay a directory of its own, such as /etc/<service>/overlay.");
            }
        }
    }
}
