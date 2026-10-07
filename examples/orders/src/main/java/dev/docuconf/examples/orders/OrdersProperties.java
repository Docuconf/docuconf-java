package dev.docuconf.examples.orders;

import dev.docuconf.Docuconf;
import dev.docuconf.Secret;
import dev.docuconf.UrlSchemes;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the orders service. Each property is the environment variable Spring binds to it: {@code port} is
 * {@code ORDERS_PORT}, {@code databaseUrl} is {@code ORDERS_DATABASEURL}. The Javadoc is the contract's
 * description.
 *
 * @param port HTTP listen port
 * @param logLevel Minimum level of the log lines the service writes
 * @param databaseUrl Postgres connection URL for the orders database
 * @param allowedOrigins Origins allowed to call the API from a browser
 * @param requestTimeout Time allowed to answer one request
 * @param workerCount Background workers that process new orders
 */
@Docuconf(service = "orders")
@Validated
@ConfigurationProperties("orders")
public record OrdersProperties(
        @Min(1) @Max(65535) @DefaultValue("8080") int port,
        @DefaultValue("info") LogLevel logLevel,
        // @Secret: the platform must supply it from a Secret, and docuconf never prints it.
        @NotNull @Secret @UrlSchemes("postgres") URI databaseUrl,
        @NotEmpty @DefaultValue("http://localhost:3000") List<String> allowedOrigins,
        @DurationMin(seconds = 1) @DurationMax(minutes = 5) @DefaultValue("30s") Duration requestTimeout,
        @Min(1) @Max(64) @DefaultValue("4") int workerCount) {

    /** The contract's enum values are the constant names, so they are spelled as the variable takes them. */
    public enum LogLevel { debug, info, warn, error }
}
