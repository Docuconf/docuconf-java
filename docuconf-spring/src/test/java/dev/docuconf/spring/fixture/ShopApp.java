package dev.docuconf.spring.fixture;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** A minimal app for the startup tests. */
@SpringBootApplication
@EnableConfigurationProperties(ShopProperties.class)
public class ShopApp {
}
