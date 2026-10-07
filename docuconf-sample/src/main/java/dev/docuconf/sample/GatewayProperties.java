package dev.docuconf.sample;

import dev.docuconf.ConfigOverlay;
import dev.docuconf.Docuconf;
import dev.docuconf.Examples;
import dev.docuconf.Group;
import dev.docuconf.Json;
import dev.docuconf.Reload;
import dev.docuconf.Secret;
import dev.docuconf.UrlSchemes;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Gateway settings, as a JavaBean: defaults are field initializers and application.yml values. The platform may
 * override them in a mounted overlay, which the gateway reloads without a restart.
 */
@Docuconf
@ConfigOverlay(value = "/etc/gateway/overlay/gateway.yaml", reload = Reload.WATCH,
        description = "Platform overrides, layered over the application*.yml files")
@Validated
@ConfigurationProperties("gateway")
public class GatewayProperties {

    /** Log levels. */
    public enum LogLevel { DEBUG, INFO, WARN, ERROR }

    /** Namespace the gateway runs in, for metrics labels. */
    @NotBlank
    private String podNamespace;

    /** Minimum log level emitted. */
    private LogLevel logLevel = LogLevel.INFO;

    /** HTTPS listen port. */
    @Min(1)
    @Max(65535)
    @Group("http")
    private int port = 8443;

    /** Fraction of requests to trace, from 0 to 1. */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double traceSampling = 0.1;

    /** Whether to compress responses. */
    @Group("http")
    private boolean compression = true;

    /** Upstream request timeout. */
    @DurationMin(seconds = 1)
    @DurationMax(minutes = 5)
    @Group("http")
    private Duration upstreamTimeout = Duration.ofSeconds(30);

    /** Session store connection string. */
    @NotNull
    @Secret
    @UrlSchemes({"redis", "rediss"})
    private URI sessionStoreUrl;

    /** Origins allowed to call the gateway from a browser. */
    @Size(max = 10)
    @Examples("https://app.example.com,https://admin.example.com")
    @Group("http")
    private List<String> allowedOrigins = List.of();

    /** Ports the health checker probes on each upstream. */
    private List<@Min(1) @Max(65535) Integer> probePorts = List.of(8080, 9090);

    /** Default per-client rate limits. */
    @Json
    @NotNull
    private RateLimits rateLimits;

    /** Cloud region the gateway runs in, such as eu-west-1. */
    @Pattern(regexp = "[a-z]{2}-[a-z]+-[0-9]")
    private String region = "eu-west-1";

    /** Admin API settings. */
    private final Admin admin = new Admin();

    /**
     * Per-client rate limits.
     *
     * @param perMinute Requests allowed per minute
     * @param burst Extra requests allowed in a burst
     */
    public record RateLimits(@Min(1) int perMinute, @Min(0) int burst) {
    }

    /** Admin API settings. */
    @Group("admin")
    public static class Admin {

        /** Key that authorizes calls to the admin API. */
        @NotBlank
        @Secret
        private String apiKey;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public String getPodNamespace() {
        return podNamespace;
    }

    public void setPodNamespace(String podNamespace) {
        this.podNamespace = podNamespace;
    }

    public LogLevel getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(LogLevel logLevel) {
        this.logLevel = logLevel;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public double getTraceSampling() {
        return traceSampling;
    }

    public void setTraceSampling(double traceSampling) {
        this.traceSampling = traceSampling;
    }

    public boolean isCompression() {
        return compression;
    }

    public void setCompression(boolean compression) {
        this.compression = compression;
    }

    public Duration getUpstreamTimeout() {
        return upstreamTimeout;
    }

    public void setUpstreamTimeout(Duration upstreamTimeout) {
        this.upstreamTimeout = upstreamTimeout;
    }

    public URI getSessionStoreUrl() {
        return sessionStoreUrl;
    }

    public void setSessionStoreUrl(URI sessionStoreUrl) {
        this.sessionStoreUrl = sessionStoreUrl;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<Integer> getProbePorts() {
        return probePorts;
    }

    public void setProbePorts(List<Integer> probePorts) {
        this.probePorts = probePorts;
    }

    public RateLimits getRateLimits() {
        return rateLimits;
    }

    public void setRateLimits(RateLimits rateLimits) {
        this.rateLimits = rateLimits;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Admin getAdmin() {
        return admin;
    }
}
