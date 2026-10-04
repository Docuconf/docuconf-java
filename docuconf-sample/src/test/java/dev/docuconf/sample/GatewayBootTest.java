package dev.docuconf.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.TlsKeyPair;
import dev.docuconf.spring.DocuconfValidationException;
import dev.docuconf.testing.TestCerts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Boots the sample gateway against real files under DOCUCONF_FILE_ROOT. */
class GatewayBootTest {

    @TempDir
    Path root;

    final Map<String, Object> env = new HashMap<>();

    @BeforeEach
    void files() throws IOException {
        env.put("DOCUCONF_FILE_ROOT", root.toString());
        env.put("GATEWAY_PODNAMESPACE", "edge");
        env.put("GATEWAY_SESSIONSTOREURL", "rediss://:session-secret@cache.internal:6380");
        env.put("GATEWAY_ADMIN_APIKEY", "admin-secret");
        env.put("GATEWAY_RATELIMITS", "{\"perMinute\":600,\"burst\":50}");
        env.put("GATEWAY_FILES_PARTNERKEYSTOREPASSWORD", "changeit");
        TestCerts.Issued ca = TestCerts.ca("Gateway CA");
        TestCerts.Issued leaf = TestCerts.leaf(TestCerts.ec(), ca, java.time.Instant.now().minus(Duration.ofDays(1)),
                java.time.Instant.now().plus(Duration.ofDays(90)), "gateway.internal", "*.example.com");
        TestCerts.writeTls(root.resolve("etc/gateway/tls"), TestCerts.traditional(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, null);
        write("etc/gateway/routes/routes.yaml", "routes:\n  - match: /api\n    upstream: https://api.internal\n");
        write("etc/gateway/ca/bundle.pem", TestCerts.pem(ca.certificate()));
        write("etc/gateway/partner/keystore.p12",
                TestCerts.keystore("PKCS12", TestCerts.selfSigned(TestCerts.rsa(), 365), "changeit"));
        write("etc/gateway/license/license.key", "ABCDE-12345-FGHIJ-67890\n");
        write("data/geoip/GeoLite2-City.mmdb", new byte[] {0, 1, 2});
    }

    private void write(String relative, Object content) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        if (content instanceof byte[] b) {
            Files.write(p, b);
        } else {
            Files.writeString(p, content.toString());
        }
    }

    private ConfigurableApplicationContext run() {
        return new SpringApplicationBuilder(GatewayApplication.class).web(WebApplicationType.NONE)
                .environment(environment(env))
                .run();
    }

    @Test
    void bootsWithEveryInputAndTheProdProfile() throws Exception {
        env.put("SPRING_PROFILES_ACTIVE", "prod");
        env.put("GATEWAY_UPSTREAMTIMEOUT", "PT1M30S");
        env.put("GATEWAY_PROBEPORTS", "8080,8081,8082");
        try (ConfigurableApplicationContext ctx = run()) {
            GatewayProperties p = ctx.getBean(GatewayProperties.class);
            assertEquals(GatewayProperties.LogLevel.WARN, p.getLogLevel(), "from application-prod.yml");
            assertEquals(0.01, p.getTraceSampling());
            assertEquals(Duration.ofSeconds(90), p.getUpstreamTimeout());
            assertEquals(List.of(8080, 8081, 8082), p.getProbePorts());
            assertEquals(List.of("https://app.example.com"), p.getAllowedOrigins());
            assertEquals(new GatewayProperties.RateLimits(600, 50), p.getRateLimits());
            assertEquals("admin-secret", p.getAdmin().getApiKey());
            GatewayFiles f = ctx.getBean(GatewayFiles.class);
            assertEquals("/api", f.routes().routes().get(0).match());
            TlsKeyPair tls = f.servingTls();
            assertNotNull(tls.sslContext());
            assertNotNull(f.partnerKeystore().load("changeit".toCharArray()));
            assertEquals(1, f.upstreamCa().certificates().size());
            assertEquals("ABCDE-12345-FGHIJ-67890\n", f.license());
            assertEquals(root.resolve("data/geoip/GeoLite2-City.mmdb"), f.geoip());
        }
    }

    @Test
    void reportsEveryProblem() throws Exception {
        env.remove("GATEWAY_PODNAMESPACE");
        env.put("GATEWAY_SESSIONSTOREURL", "http://:session-secret@cache.internal");
        env.put("GATEWAY_PORT", "70000");
        env.put("GATEWAY_REGION", "Europe");
        env.put("GATEWAY_UPSTREAMTIMEOUT", "PT10M");
        env.put("GATEWAY_FILES_PARTNERKEYSTOREPASSWORD", "wrong");
        write("etc/gateway/license/license.key", "nope\n");
        TestCerts.Issued wrong = TestCerts.selfSigned(TestCerts.ed25519(), 20, "elsewhere.internal");
        TestCerts.writeTls(root.resolve("etc/gateway/tls"), TestCerts.pkcs8(wrong.keys().getPrivate()),
                new X509Certificate[] {wrong.certificate()}, null);
        Throwable t = assertThrows(Throwable.class, () -> run().close());
        while (!(t instanceof DocuconfValidationException) && t.getCause() != null) {
            t = t.getCause();
        }
        DocuconfValidationException e = (DocuconfValidationException) t;
        List<String> codes = e.getViolations().stream().map(v -> v.code().id() + " " + v.input()).sorted()
                .collect(Collectors.toList());
        assertEquals(List.of(
                "certificate_expiring serving-tls",
                "certificate_invalid serving-tls",
                "certificate_name_mismatch serving-tls",
                "certificate_name_mismatch serving-tls",
                "invalid_scheme GATEWAY_SESSIONSTOREURL",
                "keystore_unreadable partner-keystore",
                "missing_required GATEWAY_PODNAMESPACE",
                "out_of_range GATEWAY_PORT",
                "out_of_range GATEWAY_UPSTREAMTIMEOUT",
                "pattern_mismatch GATEWAY_REGION",
                "pattern_mismatch license"), codes);
        assertFalse(e.getMessage().contains("session-secret"));
        assertTrue(e.getMessage().contains("GATEWAY_PORT: is above max 65535 (got 70000)"), e.getMessage());
    }

    /** An environment whose variables are {@code env}, set before Spring Boot reads profiles and config files. */
    static StandardEnvironment environment(Map<String, Object> env) {
        Map<String, Object> copy = new HashMap<>(env);
        return new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources sources) {
                super.customizePropertySources(sources);
                sources.replace(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, copy));
            }
        };
    }
}
