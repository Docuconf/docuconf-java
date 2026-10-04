package dev.docuconf.sample;

import dev.docuconf.BinaryFile;
import dev.docuconf.CaBundle;
import dev.docuconf.CaBundleFile;
import dev.docuconf.ConfigFile;
import dev.docuconf.Docuconf;
import dev.docuconf.KeyAlgorithm;
import dev.docuconf.Keystore;
import dev.docuconf.KeystoreFile;
import dev.docuconf.Reload;
import dev.docuconf.Secret;
import dev.docuconf.TextFile;
import dev.docuconf.TlsFile;
import dev.docuconf.TlsKeyPair;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The gateway's file inputs, as a record.
 *
 * @param routes Routing table: path prefixes and their upstreams
 * @param servingTls Certificate the gateway serves HTTPS with
 * @param upstreamCa Private CAs the gateway trusts for upstream TLS
 * @param partnerKeystorePassword Password for the partner mTLS keystore
 * @param partnerKeystore Client certificate for mTLS to the partner API
 * @param license Gateway licence key
 * @param geoip GeoIP database for country-based routing
 */
@Docuconf
@ConfigurationProperties("gateway.files")
public record GatewayFiles(
        @NotNull
        @ConfigFile(value = "/etc/gateway/routes/routes.yaml", pathEnv = "ROUTES_FILE", reload = Reload.WATCH,
                maxSize = 65536)
        Routes routes,

        @NotNull
        @TlsFile(value = "/etc/gateway/tls", dnsNames = {"gateway.internal", "api.example.com"},
                keyAlgorithms = {KeyAlgorithm.ECDSA, KeyAlgorithm.RSA}, minRemaining = "720h",
                reload = Reload.WATCH)
        TlsKeyPair servingTls,

        @CaBundleFile(value = "/etc/gateway/ca/bundle.pem", pathEnv = "SSL_CERT_FILE")
        CaBundle upstreamCa,

        @NotNull @Secret
        String partnerKeystorePassword,

        @KeystoreFile(value = "/etc/gateway/partner/keystore.p12", name = "partner-keystore",
                passwordProperty = "partnerKeystorePassword")
        Keystore partnerKeystore,

        @NotNull
        @TextFile(value = "/etc/gateway/license/license.key", pattern = "^[A-Z0-9]{5}(-[A-Z0-9]{5}){3}\\n?$")
        String license,

        @BinaryFile(value = "/data/geoip/GeoLite2-City.mmdb", maxSize = 134217728)
        Path geoip) {

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
     * @param upstream Upstream base URL
     * @param timeout Per-route timeout, overriding the gateway default
     */
    public record Route(@NotBlank @Pattern(regexp = "/.*") String match, @NotNull URI upstream, Duration timeout) {
    }
}
