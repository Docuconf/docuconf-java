package dev.docuconf.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Replaces the environment variables a test context sees with a map, without touching the process environment.
 * For {@code ApplicationContextRunner} and slice tests:
 *
 * <pre>{@code
 * new ApplicationContextRunner()
 *         .withInitializer(DocuconfTestEnvironment.of(Map.of("ORDERS_PORT", "0")))
 *         .withConfiguration(AutoConfigurations.of(DocuconfAutoConfiguration.class))
 *         .run(ctx -> assertThat(ctx).hasFailed());
 * }</pre>
 *
 * <p>Profiles selected by {@code SPRING_PROFILES_ACTIVE} in the map take no effect this way, because the context's
 * config files are already loaded; use {@link DocuconfTester} for those.
 */
public final class DocuconfTestEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private final Map<String, Object> env;

    private DocuconfTestEnvironment(Map<String, String> env) {
        this.env = new LinkedHashMap<>(env);
    }

    /**
     * An initializer that makes {@code env} the context's environment variables.
     *
     * @param env the variables
     * @return the initializer
     */
    public static DocuconfTestEnvironment of(Map<String, String> env) {
        return new DocuconfTestEnvironment(env);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
    }
}
