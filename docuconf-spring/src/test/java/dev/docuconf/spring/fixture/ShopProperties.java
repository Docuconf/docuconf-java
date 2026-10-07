package dev.docuconf.spring.fixture;

import dev.docuconf.CaBundle;
import dev.docuconf.CaBundleFile;
import dev.docuconf.BinaryFile;
import dev.docuconf.ConfigFile;
import dev.docuconf.ConfigOverlay;
import dev.docuconf.Docuconf;
import dev.docuconf.EnumCase;
import dev.docuconf.EnumValues;
import dev.docuconf.Json;
import dev.docuconf.KeyAlgorithm;
import dev.docuconf.Keystore;
import dev.docuconf.KeystoreFile;
import dev.docuconf.MaxLength;
import dev.docuconf.Redacted;
import dev.docuconf.Reload;
import dev.docuconf.Secret;
import dev.docuconf.TextFile;
import dev.docuconf.TlsFile;
import dev.docuconf.TlsKeyPair;
import dev.docuconf.UrlSchemes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the test shop.
 *
 * @param databaseUrl Primary Postgres connection string
 * @param port HTTP listen port
 * @param timeout Upstream request timeout
 * @param origins Origins allowed to call the API
 * @param shards Shard ids this instance owns
 * @param level Minimum log level
 * @param limits Default per-client rate limits
 * @param branches Branch codes, two to four characters each
 * @param contact Operator contact address
 * @param tls Certificate the shop serves HTTPS with
 * @param routes Routing table
 * @param license Shop licence key
 * @param keystorePassword Password of the partner keystore
 * @param partner Client certificate for the partner API
 * @param trusted Private CAs to trust
 * @param geo GeoIP database
 * @param alertEmail Where security alerts go
 * @param mode Checkout mode
 */
@Docuconf(service = "shop")
@ConfigOverlay(value = "/etc/shop/overlay/shop.yaml", description = "Platform overrides, layered over application.yml")
@Validated
@ConfigurationProperties("shop")
public record ShopProperties(
        @NotNull @Secret @UrlSchemes({"postgres", "postgresql"}) URI databaseUrl,
        @Min(1) @Max(65535) @DefaultValue("8080") int port,
        @DurationMin(seconds = 1) @DefaultValue("PT30S") Duration timeout,
        @Size(max = 3) List<String> origins,
        List<@Min(0) @Max(1023) Integer> shards,
        @DefaultValue("INFO") Level level,
        @Json @MaxLength(30) RateLimits limits,
        List<@Size(min = 2, max = 4) String> branches,
        @Email String contact,
        @NotNull @TlsFile(value = "/etc/shop/tls", dnsNames = "shop.internal", minRemaining = "720h",
                keyAlgorithms = {KeyAlgorithm.ECDSA, KeyAlgorithm.RSA}) TlsKeyPair tls,
        @NotNull @ConfigFile(value = "/etc/shop/routes/routes.yaml", reload = Reload.WATCH) Routes routes,
        @TextFile(value = "/etc/shop/license/license.key", pattern = "^[A-Z]{4}\\n?$") String license,
        @Secret String keystorePassword,
        @KeystoreFile(value = "/etc/shop/partner/keystore.p12", passwordProperty = "keystorePassword")
        Keystore partner,
        @CaBundleFile(value = "/etc/shop/ca/bundle.pem", pathEnv = "SHOP_CA_FILE") CaBundle trusted,
        @BinaryFile("/data/geo/db.mmdb") Path geo,
        @Secret @Email String alertEmail,
        @EnumValues(EnumCase.LOWER) @DefaultValue("FAST") Mode mode) {

    @Override
    public String toString() {
        return Redacted.toString(this);
    }

    /** Checkout modes. */
    public enum Mode { FAST, SAFE }

    /** Log levels. */
    public enum Level { DEBUG, INFO, WARN }

    /**
     * Rate limits.
     *
     * @param perMinute Requests per minute
     * @param burst Extra requests allowed in a burst
     */
    public record RateLimits(@Min(1) int perMinute, @Min(0) int burst) {
    }

    /**
     * The routing table.
     *
     * @param routes Routes, matched in order
     */
    public record Routes(@NotEmpty List<Route> routes) {
    }

    /**
     * One route.
     *
     * @param match Path prefix to match
     * @param upstream Upstream URL
     * @param timeout Per-route timeout
     */
    public record Route(@NotBlank @Pattern(regexp = "/.*") String match, @NotNull URI upstream, Duration timeout) {
    }
}
