package dev.docuconf.sample;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/** An API gateway that reads every kind of docuconf input. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    private static final Log LOG = LogFactory.getLog(GatewayApplication.class);

    /**
     * Starts the gateway.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    CommandLineRunner report(GatewayProperties gateway, GatewayFiles files) {
        return args -> LOG.info("gateway on port " + gateway.getPort() + " with " + files.routes().routes().size()
                + " routes, serving " + files.servingTls().certificateChain().get(0).getSubjectX500Principal());
    }
}
