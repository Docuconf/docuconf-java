package fixture;

import dev.docuconf.BinaryFile;
import dev.docuconf.CaBundle;
import dev.docuconf.CaBundleFile;
import dev.docuconf.ConfigFile;
import dev.docuconf.Docuconf;
import dev.docuconf.EnumCase;
import dev.docuconf.EnvNames;
import dev.docuconf.Examples;
import dev.docuconf.Group;
import dev.docuconf.Json;
import dev.docuconf.KeyAlgorithm;
import dev.docuconf.KeySet;
import dev.docuconf.KeySetLimits;
import dev.docuconf.Keystore;
import dev.docuconf.KeystoreFile;
import dev.docuconf.MaxLength;
import dev.docuconf.Reload;
import dev.docuconf.Secret;
import dev.docuconf.TextFile;
import dev.docuconf.TlsFile;
import dev.docuconf.TlsKeyPair;
import dev.docuconf.UrlSchemes;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.boot.convert.Delimiter;

/**
 * The shared export fixture of the conformance suite (docuconf-go conformance/export/fixture.yaml), declared with
 * Spring Boot's {@code @ConfigurationProperties}. The empty prefix and {@code EnvNames.UNDERSCORED} give the
 * fixture's variable names ({@code request-timeout} is {@code REQUEST_TIMEOUT}).
 */
@Docuconf(service = "docuconf-fixture", envNames = EnvNames.UNDERSCORED, enumCase = EnumCase.LOWER)
@ConfigurationProperties("")
public class FixtureProperties {

    /**
     * Service name, used in logs and metrics
     *
     * <p>Lower case, as a DNS label allows.
     */
    @Size(min = 2, max = 40)
    @Pattern(regexp = "^[a-z][a-z0-9-]*$")
    @Group("general")
    @Examples({"orders", "billing"})
    private String appName = "orders";

    /** Primary Postgres connection string */
    @NotNull
    @Secret
    @UrlSchemes({"postgres", "postgresql"})
    @MaxLength(2048)
    @Group("database")
    private URI databaseUrl;

    /** HTTP listen port */
    @Min(1)
    @Max(65535)
    private int port = 8080;

    /** Fraction of requests traced */
    @DecimalMin("0")
    @DecimalMax("1")
    private double traceRatio = 0.25;

    /** Serve the debug endpoints */
    private boolean debug;

    /** Upstream request timeout */
    @DurationMin(seconds = 1)
    @DurationMax(minutes = 5)
    private Duration requestTimeout = Duration.ofSeconds(90);

    /** Minimum log level */
    private LogLevel logLevel = LogLevel.INFO;

    /** CORS origins allowed to call the API */
    @Size(min = 1, max = 5)
    @Delimiter(";")
    private List<@Size(min = 1, max = 255) String> allowedOrigins;

    /** Shards this instance owns */
    private List<@Min(0) @Max(1023) Integer> shards;

    /** Keys that verify webhook signatures */
    @KeySetLimits(keyMinLength = 32, keyMaxLength = 256)
    private KeySet webhookKeys;

    // The default, {"perMinute": 60}, is in application.yml.
    /** Per-client rate limits */
    @Json
    @MaxLength(1024)
    private RateLimits rateLimits;

    /** Port the service used to listen on */
    private Long oldPort;

    /** Password of the partner keystore */
    @Secret
    private String partnerPassword;

    /** Application settings */
    @NotNull
    @Group("general")
    @ConfigFile(value = "/etc/app/settings/settings.json", pathEnv = "SETTINGS_FILE", reload = Reload.WATCH,
            maxSize = 65536)
    private Settings settings;

    /** Routing rules */
    @ConfigFile("/etc/app/rules/rules.yaml")
    private Settings rules;

    /** Feature defaults */
    @ConfigFile("/etc/app/flags/flags.toml")
    private Settings flags;

    /** Certificate the service serves HTTPS with */
    @TlsFile(value = "/etc/app/tls", reload = Reload.WATCH, dnsNames = {"app.example.test", "api.example.test"},
            keyAlgorithms = {KeyAlgorithm.ECDSA, KeyAlgorithm.ED25519}, minRemaining = "720h", requireCA = true)
    private TlsKeyPair servingTls;

    /** CAs the service trusts */
    @CaBundleFile(value = "/etc/app/trust/bundle.pem", minCertificates = 2)
    private CaBundle trust;

    /** Client certificate for the partner API */
    @KeystoreFile(value = "/etc/app/partner/keystore.p12", passwordProperty = "partnerPassword")
    private Keystore partner;

    /** Licence key */
    @TextFile(value = "/etc/app/licence/licence.key", pattern = "^[A-Z0-9-]+\\n?$", minLength = 8, maxLength = 64)
    private String licence;

    /** GeoIP database */
    @BinaryFile(value = "/data/geoip/geoip.mmdb", maxSize = 134217728)
    private Path geoip;

    /** City-level location database */
    @BinaryFile("/data/geo-db/geo.mmdb")
    private Path geoDb;

    /** Log levels, in order. */
    public enum LogLevel { DEBUG, INFO, WARN, ERROR }

    // The JSON Schemas have no descriptions: the fixture's golden contract has none.
    public record RateLimits(@NotNull @Min(1) Integer perMinute, @Min(0) Integer burst) {
    }

    public record Settings(@NotNull @Size(min = 1) String name, @NotNull @Min(1) Integer replicas, List<String> tags) {
    }

    public String getAppName() { return appName; }
    public void setAppName(String v) { appName = v; }
    public URI getDatabaseUrl() { return databaseUrl; }
    public void setDatabaseUrl(URI v) { databaseUrl = v; }
    public int getPort() { return port; }
    public void setPort(int v) { port = v; }
    public double getTraceRatio() { return traceRatio; }
    public void setTraceRatio(double v) { traceRatio = v; }
    public boolean isDebug() { return debug; }
    public void setDebug(boolean v) { debug = v; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration v) { requestTimeout = v; }
    public LogLevel getLogLevel() { return logLevel; }
    public void setLogLevel(LogLevel v) { logLevel = v; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> v) { allowedOrigins = v; }
    public List<Integer> getShards() { return shards; }
    public void setShards(List<Integer> v) { shards = v; }
    public KeySet getWebhookKeys() { return webhookKeys; }
    public void setWebhookKeys(KeySet v) { webhookKeys = v; }
    public RateLimits getRateLimits() { return rateLimits; }
    public void setRateLimits(RateLimits v) { rateLimits = v; }

    @DeprecatedConfigurationProperty(reason = "Use PORT instead", replacement = "port")
    public Long getOldPort() { return oldPort; }
    public void setOldPort(Long v) { oldPort = v; }

    public String getPartnerPassword() { return partnerPassword; }
    public void setPartnerPassword(String v) { partnerPassword = v; }
    public Settings getSettings() { return settings; }
    public void setSettings(Settings v) { settings = v; }
    public Settings getRules() { return rules; }
    public void setRules(Settings v) { rules = v; }
    public Settings getFlags() { return flags; }
    public void setFlags(Settings v) { flags = v; }
    public TlsKeyPair getServingTls() { return servingTls; }
    public void setServingTls(TlsKeyPair v) { servingTls = v; }
    public CaBundle getTrust() { return trust; }
    public void setTrust(CaBundle v) { trust = v; }
    public Keystore getPartner() { return partner; }
    public void setPartner(Keystore v) { partner = v; }
    public String getLicence() { return licence; }
    public void setLicence(String v) { licence = v; }

    @DeprecatedConfigurationProperty(reason = "Use geo-db instead", replacement = "geo-db")
    public Path getGeoip() { return geoip; }
    public void setGeoip(Path v) { geoip = v; }

    public Path getGeoDb() { return geoDb; }
    public void setGeoDb(Path v) { geoDb = v; }
}
