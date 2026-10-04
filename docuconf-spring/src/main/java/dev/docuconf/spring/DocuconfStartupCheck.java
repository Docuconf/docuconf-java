package dev.docuconf.spring;

import dev.docuconf.check.TerminationLog;
import dev.docuconf.check.Violation;
import dev.docuconf.contract.ContractBundle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Runs the docuconf checks once the environment is complete (including {@code @PropertySource}s) and before any
 * bean is created, so no {@code @ConfigurationProperties} bean binds an invalid value. On failure it writes the
 * termination log and throws {@link DocuconfValidationException} with every violation.
 */
public class DocuconfStartupCheck implements BeanFactoryPostProcessor, EnvironmentAware, BeanClassLoaderAware,
        PriorityOrdered {

    private static final Log LOG = LogFactory.getLog(DocuconfStartupCheck.class);

    /** The bean name under which the checker is registered. */
    public static final String CHECKER_BEAN = "docuconfChecker";

    /** The bean name under which the file registry is registered. */
    public static final String FILES_BEAN = "docuconfFiles";

    private ConfigurableEnvironment environment;
    private ClassLoader classLoader = DocuconfStartupCheck.class.getClassLoader();
    private final Clock clock;

    /** Creates the check with the system clock. */
    public DocuconfStartupCheck() {
        this(Clock.systemUTC());
    }

    /**
     * Creates the check.
     *
     * @param clock the time used for certificate validity
     */
    public DocuconfStartupCheck(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = (ConfigurableEnvironment) environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<ContractBundle> bundles;
        try {
            bundles = DocuconfChecker.load(classLoader);
        } catch (IOException e) {
            throw new UncheckedIOException("docuconf: cannot read META-INF/docuconf/contract.json", e);
        }
        if (bundles.isEmpty()) {
            LOG.warn("docuconf: no META-INF/docuconf/contract.json on the class path; is docuconf-processor"
                    + " configured as an annotation processor?");
        }
        DocuconfChecker checker = new DocuconfChecker(environment, bundles, classLoader, clock);
        List<Violation> violations = checker.check();
        for (String w : checker.warnings()) {
            LOG.warn("docuconf: " + w);
        }
        if (!violations.isEmpty()) {
            TerminationLog.write(violations, environment::getProperty);
            throw new DocuconfValidationException(violations);
        }
        if (!beanFactory.containsSingleton(FILES_BEAN)) {
            beanFactory.registerSingleton(FILES_BEAN, checker.files());
            beanFactory.registerSingleton(CHECKER_BEAN, checker);
        }
        LOG.debug("docuconf: configuration satisfies " + bundles.size() + " contract(s)");
    }
}
