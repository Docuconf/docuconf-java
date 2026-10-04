package dev.docuconf.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;

/**
 * Validates every {@code @Docuconf @ConfigurationProperties} class and its file inputs at startup.
 *
 * <p>Set {@code docuconf.enabled=false} to skip the check, for example in slice tests or build-time tasks that
 * start the context without production configuration.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "docuconf.enabled", havingValue = "true", matchIfMissing = true)
public class DocuconfAutoConfiguration {

    /** Creates the configuration. */
    public DocuconfAutoConfiguration() {
    }

    /**
     * The startup check.
     *
     * @return the bean factory post-processor
     */
    @Bean
    public static DocuconfStartupCheck docuconfStartupCheck() {
        return new DocuconfStartupCheck();
    }

    /**
     * Hands checked file inputs to {@code @ConfigurationProperties} binding.
     *
     * @param files the registry the startup check filled
     * @return the converter
     */
    @Bean
    @ConfigurationPropertiesBinding
    public static DocuconfFileConverter docuconfFileConverter(ObjectProvider<DocuconfFiles> files) {
        return new DocuconfFileConverter(files.getIfAvailable(DocuconfFiles::new));
    }

    /**
     * Binds {@code @Json} properties from one JSON variable.
     *
     * @return the converter
     */
    @Bean
    @ConfigurationPropertiesBinding
    public static DocuconfJsonConverter docuconfJsonConverter() {
        return new DocuconfJsonConverter();
    }

    /**
     * Reloads file inputs declared with {@code reload = WATCH}.
     *
     * @param checker the startup checker, registered by {@link DocuconfStartupCheck}
     * @return the watcher
     */
    @Bean
    public DocuconfFileWatcher docuconfFileWatcher(ObjectProvider<DocuconfChecker> checker) {
        return new DocuconfFileWatcher(checker.getIfAvailable(), 500);
    }
}
