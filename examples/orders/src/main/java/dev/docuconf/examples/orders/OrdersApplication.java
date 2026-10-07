package dev.docuconf.examples.orders;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The orders service. docuconf checks the environment against the contract before Spring binds
 * {@link OrdersProperties}, so a bad value stops startup with every problem listed.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@RestController
public class OrdersApplication {

    private final OrdersProperties config;

    OrdersApplication(OrdersProperties config) {
        this.config = config;
    }

    /**
     * Starts the service.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }

    @GetMapping("/healthz")
    String healthz() {
        return "ok";
    }

    /** The typed configuration, with the secret redacted. */
    @GetMapping("/config")
    Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("port", config.port());
        out.put("logLevel", config.logLevel());
        out.put("databaseUrl", "***");
        out.put("allowedOrigins", config.allowedOrigins());
        out.put("requestTimeout", config.requestTimeout());
        out.put("workerCount", config.workerCount());
        return out;
    }
}
