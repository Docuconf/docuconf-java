package dev.docuconf.examples.orders;

import dev.docuconf.Docuconf;
import dev.docuconf.EnumCase;
import dev.docuconf.MaxLength;
import dev.docuconf.Redacted;
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
 * {@code ORDERS_PORT}, {@code databaseUrl} is {@code ORDERS_DATABASEURL}. The first sentence of each property's
 * Javadoc is the contract's description, and the rest is its details, longer docs for {@code docuconf docs}.
 *
 * @param port HTTP listen port
 * @param logLevel Minimum level of the log lines the service writes
 * @param databaseUrl Postgres connection URL for the orders database
 * @param allowedOrigins Origins allowed to call the API from a browser
 * @param requestTimeout Time allowed to answer one request
 * @param workerCount Background workers that process new orders
 *        <p>Each worker holds one connection from the pool of {@code databaseUrl}, so keep this below the
 *        database's connection limit.
 *        <ul>
 *          <li>Raise it when the order queue backs up.</li>
 *          <li>Lower it when the database is the bottleneck.</li>
 *        </ul>
 */
@Docuconf(service = "orders", enumCase = EnumCase.LOWER)
@Validated
@ConfigurationProperties("orders")
public record OrdersProperties(
        @Min(1) @Max(65535) @DefaultValue("8080") int port,
        @DefaultValue("INFO") LogLevel logLevel,
        // @Secret: the platform must supply it from a Secret, and docuconf never prints it. @MaxLength bounds the
        // URL in characters; a longer one fails startup with out_of_range.
        @NotNull @Secret @UrlSchemes("postgres") @MaxLength(2048) URI databaseUrl,
        @NotEmpty @DefaultValue("http://localhost:3000") List<String> allowedOrigins,
        @DurationMin(seconds = 1) @DurationMax(minutes = 5) @DefaultValue("30s") Duration requestTimeout,
        @Min(1) @Max(64) @DefaultValue("4") int workerCount) {

    /** Log levels. The contract spells them in lower case (enumCase); the app accepts any case, as Spring does. */
    public enum LogLevel { DEBUG, INFO, WARN, ERROR }

    /** Prints the secret as [redacted]; a record's generated toString() would print it. */
    @Override
    public String toString() {
        return Redacted.toString(this);
    }
}
