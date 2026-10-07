package dev.docuconf.spring;

import dev.docuconf.check.Violation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Runs the startup check against an environment you give it, for unit tests of your configuration.
 *
 * <p>The environment variables come from a map, never from the process, and nothing global is changed. Spring Boot
 * loads {@code application*.yml} and profiles as it does at startup, so the verdict is the one the app would give;
 * no bean of yours is created and no watcher thread is started.
 *
 * <pre>{@code
 * var result = DocuconfTester.env(Map.of("ORDERS_PORT", "0", "ORDERS_LOGLEVEL", "warn")).check();
 * assertEquals(List.of("out_of_range ORDERS_PORT"), result.codes());
 * }</pre>
 */
public final class DocuconfTester {

    private final Map<String, Object> env = new LinkedHashMap<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private Clock clock = Clock.systemUTC();

    private DocuconfTester(Map<String, String> env) {
        this.env.putAll(env);
    }

    /**
     * Starts a check with these environment variables and no others.
     *
     * @param env the variables, such as {@code ORDERS_PORT=8080}
     * @return the tester
     */
    public static DocuconfTester env(Map<String, String> env) {
        return new DocuconfTester(env);
    }

    /**
     * Reads file inputs under a directory, as {@code DOCUCONF_FILE_ROOT} does: {@code /etc/orders/tls} is read
     * from {@code root/etc/orders/tls}.
     *
     * @param root the directory
     * @return this tester
     */
    public DocuconfTester fileRoot(Path root) {
        env.put("DOCUCONF_FILE_ROOT", root.toAbsolutePath().toString());
        return this;
    }

    /**
     * Adds Spring properties with the precedence of command-line arguments, such as
     * {@code spring.profiles.active=prod}.
     *
     * @param properties the properties
     * @return this tester
     */
    public DocuconfTester properties(Map<String, String> properties) {
        this.properties.putAll(properties);
        return this;
    }

    /**
     * Sets the time used for certificate validity.
     *
     * @param clock the clock
     * @return this tester
     */
    public DocuconfTester clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * Runs the check.
     *
     * @return every violation and warning
     */
    public Result check() {
        Probe probe = new Probe(clock);
        SpringApplicationBuilder app = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false).environment(environment(env, properties))
                .initializers(ctx -> {
                    probe.environment = ctx.getEnvironment();
                    ctx.addBeanFactoryPostProcessor(probe);
                });
        try (ConfigurableApplicationContext ignored = app.run()) {
            if (probe.error != null) {
                throw probe.error;
            }
            return new Result(probe.violations, probe.warnings);
        }
    }

    /**
     * What the check found.
     *
     * @param violations every violation, empty when the configuration is valid
     * @param warnings hints such as a typo'd variable name or a deprecated variable that is set
     */
    public record Result(List<Violation> violations, List<String> warnings) {

        /**
         * Whether the configuration is valid.
         *
         * @return {@code true} when there are no violations
         */
        public boolean ok() {
            return violations.isEmpty();
        }

        /**
         * Each violation as {@code "code INPUT"}, sorted, which makes compact assertions.
         *
         * @return the codes
         */
        public List<String> codes() {
            return violations.stream().map(v -> v.code().id() + " " + v.input()).sorted().toList();
        }
    }

    /**
     * An environment whose variables are exactly {@code env} and which has no JVM system properties, set before
     * Spring Boot reads profiles and config files.
     *
     * @param env the environment variables
     * @param properties properties that take precedence over everything else
     * @return the environment
     */
    static StandardEnvironment environment(Map<String, Object> env, Map<String, Object> properties) {
        Map<String, Object> vars = new LinkedHashMap<>(env);
        Map<String, Object> props = new LinkedHashMap<>(properties);
        return new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources sources) {
                super.customizePropertySources(sources);
                sources.replace(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
                sources.replace(SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
                if (!props.isEmpty()) {
                    sources.addFirst(new MapPropertySource("docuconfTesterProperties", props));
                }
            }
        };
    }

    /** An application with no beans of its own. */
    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    /** Runs the checker once the environment is complete and keeps the outcome instead of failing startup. */
    private static final class Probe implements BeanFactoryPostProcessor {
        private final Clock clock;
        private ConfigurableEnvironment environment;
        private List<Violation> violations = List.of();
        private List<String> warnings = List.of();
        private RuntimeException error;

        Probe(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            ClassLoader loader = beanFactory.getBeanClassLoader() != null ? beanFactory.getBeanClassLoader()
                    : DocuconfTester.class.getClassLoader();
            try {
                DocuconfChecker checker = new DocuconfChecker(environment, DocuconfChecker.load(loader), loader,
                        clock);
                violations = List.copyOf(checker.check());
                warnings = List.copyOf(new ArrayList<>(checker.warnings()));
            } catch (IOException e) {
                error = new UncheckedIOException(e);
            } catch (RuntimeException e) {
                error = e;
            }
        }
    }
}
