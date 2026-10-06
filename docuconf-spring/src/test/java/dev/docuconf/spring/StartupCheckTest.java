package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.check.Violation;
import dev.docuconf.spring.fixture.ShopProperties;
import dev.docuconf.testing.TestCerts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;

class StartupCheckTest {

    @TempDir
    Path tmp;

    ShopFixture shop;

    @BeforeEach
    void setUp() {
        shop = new ShopFixture(tmp);
    }

    private DocuconfValidationException fails() {
        Throwable t = assertThrows(Throwable.class, () -> shop.run().close());
        while (t != null && !(t instanceof DocuconfValidationException)) {
            t = t.getCause();
        }
        assertNotNull(t, "expected a DocuconfValidationException");
        return (DocuconfValidationException) t;
    }

    private static List<String> codes(DocuconfValidationException e) {
        return e.getViolations().stream().map(v -> v.code().id() + " " + v.input()).sorted()
                .collect(Collectors.toList());
    }

    @Test
    void validConfigurationBindsEverything() throws Exception {
        shop.env.put("SHOP_PORT", "9090");
        shop.env.put("SHOP_TIMEOUT", "PT1M30S");
        shop.env.put("SHOP_ORIGINS", "https://a.example,https://b.example");
        shop.env.put("SHOP_LEVEL", "warn");
        shop.env.put("SHOP_LIMITS", "{\"perMinute\":60,\"burst\":10}");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertEquals(9090, p.port());
            assertEquals(Duration.ofSeconds(90), p.timeout());
            assertEquals(List.of("https://a.example", "https://b.example"), p.origins());
            assertEquals(ShopProperties.Level.WARN, p.level());
            assertEquals(new ShopProperties.RateLimits(60, 10), p.limits());
            assertEquals(tmp.resolve("etc/shop/tls"), p.tls().directory());
            assertNotNull(p.tls().sslContext());
            assertEquals("/api", p.routes().routes().get(0).match());
            assertEquals(Duration.ofSeconds(5), p.routes().routes().get(0).timeout());
            assertEquals("ABCD\n", p.license());
            assertNotNull(p.partner().load(ShopFixture.KS_PASSWORD.toCharArray()));
            assertEquals(1, p.trusted().certificates().size());
            assertEquals(tmp.resolve("data/geo/db.mmdb"), p.geo());
            DocuconfFiles files = ctx.getBean(DocuconfFiles.class);
            assertEquals("ABCD\n", files.get("license", String.class).orElseThrow());
        }
    }

    @Test
    void emptyMeansUnsetExceptForStrings() {
        shop.env.put("SHOP_PORT", "");
        shop.env.put("SHOP_TIMEOUT", "");
        shop.env.put("SHOP_CONTACT", "");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertEquals(8080, p.port());
            assertEquals(Duration.ofSeconds(30), p.timeout());
        }
        shop.env.put("SHOP_DATABASEURL", "");
        assertEquals(List.of("missing_required SHOP_DATABASEURL"), codes(fails()));
    }

    @Test
    void optionalFilesMayBeAbsent() throws Exception {
        Files.delete(tmp.resolve("etc/shop/license/license.key"));
        Files.delete(tmp.resolve("data/geo/db.mmdb"));
        try (ConfigurableApplicationContext ctx = shop.run()) {
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertNull(p.license());
            assertNull(p.geo());
        }
    }

    @Test
    void allViolationsReportedTogetherWithoutSecrets() throws Exception {
        shop.env.put("SHOP_PORT", "eighty");
        shop.env.put("SHOP_DATABASEURL", "mysql://shop:" + ShopFixture.DB_SECRET + "@db/shop");
        shop.env.put("SHOP_TIMEOUT", "1m30s");
        shop.env.put("SHOP_ORIGINS", "a,b,c,d");
        shop.env.put("SHOP_LEVEL", "LOUD");
        shop.env.put("SHOP_LIMITS", "{\"perMinute\":0}");
        shop.env.put("SHOP_CONTACT", "not-an-email");
        shop.env.put("SHOP_KEYSTOREPASSWORD", "wrong-" + ShopFixture.KS_PASSWORD);
        TestCerts.Issued expiring = TestCerts.selfSigned(TestCerts.ec(), 10, "shop.internal");
        shop.tls(expiring, TestCerts.pkcs8(expiring.keys().getPrivate()));
        shop.write("etc/shop/routes/routes.yaml", "routes:\n  - match: api\n    upstream: https://x\n");
        shop.write("etc/shop/license/license.key", "abcd\n");
        Files.delete(tmp.resolve("etc/shop/ca/bundle.pem"));
        shop.write("etc/shop/ca/bundle.pem", "not a certificate\n");

        DocuconfValidationException e = fails();
        assertEquals(List.of(
                "certificate_expiring tls",
                "file_malformed trusted",
                "invalid_scheme SHOP_DATABASEURL",
                "invalid_type SHOP_CONTACT",
                "invalid_type SHOP_PORT",
                "invalid_type SHOP_TIMEOUT",
                "keystore_unreadable partner",
                "not_in_enum SHOP_LEVEL",
                "pattern_mismatch license",
                "schema_mismatch SHOP_LIMITS",
                "schema_mismatch routes",
                "too_many_items SHOP_ORIGINS"), codes(e));
        String all = e.getMessage() + e.getViolations();
        assertFalse(all.contains(ShopFixture.DB_SECRET), all);
        assertFalse(all.contains(ShopFixture.KS_PASSWORD), all);
        assertTrue(all.contains("[invalid_type] SHOP_PORT: is not a valid integer (got \"eighty\")"), all);

        String log = Files.readString(tmp.resolve("termination-log"));
        assertTrue(log.contains("[certificate_expiring] tls"), log);
        assertFalse(log.contains(ShopFixture.DB_SECRET));

        FailureAnalysis analysis = new DocuconfFailureAnalyzer().analyze(new IllegalStateException(e));
        assertTrue(analysis.getDescription().contains("12 problems"), analysis.getDescription());
        assertTrue(analysis.getDescription().contains("[too_many_items] SHOP_ORIGINS: has 4 items; at most 3 allowed"),
                analysis.getDescription());
        assertFalse(analysis.getDescription().contains(ShopFixture.DB_SECRET));
    }

    @Test
    void listItemBounds() throws Exception {
        shop.env.put("SHOP_SHARDS", "0,7,1023");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            assertEquals(List.of(0, 7, 1023), ctx.getBean(ShopProperties.class).shards());
        }
        shop.env.put("SHOP_SHARDS", "3,1024");
        DocuconfValidationException e = fails();
        assertEquals(List.of("out_of_range SHOP_SHARDS"), codes(e));
        assertTrue(e.getViolations().get(0).message().contains("itemMax 1023"), e.getViolations().toString());
        shop.env.put("SHOP_SHARDS", "-1");
        assertEquals(List.of("out_of_range SHOP_SHARDS"), codes(fails()));
        shop.env.put("SHOP_SHARDS", "1,x");
        assertEquals(List.of("invalid_type SHOP_SHARDS"), codes(fails()));
    }

    @Test
    void missingRequiredVariableAndFiles() throws Exception {
        shop.env.remove("SHOP_DATABASEURL");
        Files.delete(tmp.resolve("etc/shop/routes/routes.yaml"));
        try (var s = Files.walk(tmp.resolve("etc/shop/tls"))) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        assertEquals(List.of("file_missing routes", "file_missing tls", "missing_required SHOP_DATABASEURL"),
                codes(fails()));
    }

    @Test
    void tlsNameAndKeyMismatch() {
        TestCerts.Issued wrongName = TestCerts.selfSigned(TestCerts.rsa(), 90, "other.internal");
        shop.tls(wrongName, TestCerts.traditional(TestCerts.rsa().getPrivate()));
        assertEquals(List.of("certificate_name_mismatch tls", "key_mismatch tls"), codes(fails()));
    }

    @Test
    void malformedConfigFile() {
        shop.write("etc/shop/routes/routes.yaml", "routes: [ {match: /a\n");
        DocuconfValidationException e = fails();
        assertEquals(List.of("file_malformed routes"), codes(e));
        shop.write("etc/shop/routes/routes.yaml", "routes: []\nextra: 1\n");
        Violation v = fails().getViolations().get(0);
        assertEquals("schema_mismatch", v.code().id());
        assertTrue(v.message().contains("extra"), v.message());
    }

    @Test
    void pathEnvAndFileRoot() throws Exception {
        Path other = tmp.resolve("elsewhere/ca.pem");
        Files.createDirectories(other.getParent());
        Files.move(tmp.resolve("etc/shop/ca/bundle.pem"), other);
        // The pathEnv value is absolute, so DOCUCONF_FILE_ROOT applies to it too.
        shop.env.put("SHOP_CA_FILE", "/elsewhere/ca.pem");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            assertEquals(other, ctx.getBean(ShopProperties.class).trusted().path());
        }
    }

    @Test
    void canBeDisabled() {
        shop.env.remove("SHOP_DATABASEURL");
        shop.env.put("SHOP_PORT", "70000");
        // Spring's own validation still applies when docuconf is off.
        Throwable t = assertThrows(Throwable.class, () -> shop.run("--docuconf.enabled=false").close());
        while (t.getCause() != null) {
            t = t.getCause();
        }
        assertFalse(t instanceof DocuconfValidationException);
    }

    @Test
    void watchedConfigFileReloads() throws Exception {
        try (ConfigurableApplicationContext ctx = shop.run()) {
            DocuconfFiles files = ctx.getBean(DocuconfFiles.class);
            CountDownLatch changed = new CountDownLatch(1);
            AtomicReference<Object> latest = new AtomicReference<>();
            files.onChange("routes", v -> {
                latest.set(v);
                changed.countDown();
            });
            // An invalid update is ignored.
            shop.write("etc/shop/routes/routes.yaml", "routes: []\n");
            Thread.sleep(1500);
            assertEquals("/api", files.get("routes", ShopProperties.Routes.class).orElseThrow().routes().get(0).match());
            shop.write("etc/shop/routes/routes.yaml", "routes:\n  - match: /v2\n    upstream: https://v2.internal\n");
            assertTrue(changed.await(20, TimeUnit.SECONDS), "no reload");
            ShopProperties.Routes routes = assertInstanceOf(ShopProperties.Routes.class, latest.get());
            assertEquals("/v2", routes.routes().get(0).match());
        }
    }

    @Test
    void unresolvedInjectorReferencesAreReportedWithoutTheirValues() throws Exception {
        String dbRef = "vault:secret/data/shop/db#url";
        String ksRef = "op://platform/shop-keystore/password";
        shop.env.put("SHOP_DATABASEURL", dbRef);
        shop.env.put("SHOP_KEYSTOREPASSWORD", ksRef);
        DocuconfValidationException e = fails();
        assertEquals(List.of("invalid_type SHOP_DATABASEURL", "invalid_type SHOP_KEYSTOREPASSWORD",
                "keystore_unreadable partner"), codes(e));
        String all = e.getMessage() + e.getViolations();
        assertTrue(all.contains("[invalid_type] SHOP_DATABASEURL: holds an unresolved vault: reference; the injector"
                + " that should resolve it did not run"), all);
        assertTrue(all.contains("[invalid_type] SHOP_KEYSTOREPASSWORD: holds an unresolved op:// reference"), all);
        assertFalse(all.contains("secret/data/shop"), all);
        assertFalse(all.contains("shop-keystore"), all);

        String log = Files.readString(tmp.resolve("termination-log"));
        assertTrue(log.contains("[invalid_type] SHOP_DATABASEURL: holds an unresolved vault: reference"), log);
        assertFalse(log.contains("secret/data/shop"), log);
        assertFalse(log.contains("shop-keystore"), log);

        shop.env.put("SHOP_DATABASEURL", "ref+vault://shop/db#url");
        shop.env.put("SHOP_KEYSTOREPASSWORD", ShopFixture.KS_PASSWORD);
        DocuconfValidationException vals = fails();
        assertEquals(List.of("invalid_type SHOP_DATABASEURL"), codes(vals));
        assertTrue(vals.getMessage().contains("holds an unresolved ref+ reference"), vals.getMessage());
        assertFalse(vals.getMessage().contains("shop/db"), vals.getMessage());
    }

    @Test
    void referencesInNonSecretVariablesAreLeftToTheirConstraints() {
        shop.env.put("SHOP_CONTACT", "vault:ops");
        DocuconfValidationException e = fails();
        assertEquals(List.of("invalid_type SHOP_CONTACT"), codes(e));
        assertTrue(e.getMessage().contains("(@Email)"), e.getMessage());
    }
}
