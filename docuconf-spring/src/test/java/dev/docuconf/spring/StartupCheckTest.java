package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.Keystore;
import dev.docuconf.check.Violation;
import dev.docuconf.spring.fixture.ReloadRecorder;
import dev.docuconf.spring.fixture.ShopProperties;
import dev.docuconf.testing.TestCerts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

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
        shop.env.put("SHOP_LEVEL", "WARN");
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
    void valuesBindAsSpecSectionFiveReadsThem() throws Exception {
        shop.env.put("SHOP_PORT", "+010");
        shop.env.put("SHOP_SHARDS", "007,+8");
        shop.env.put("SHOP_TIMEOUT", "PT1,5S");
        shop.env.put("SHOP_WEBHOOKKEYS", "old-key-0123, new-key-0123");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertEquals(10, p.port(), "010 is decimal, never octal");
            assertEquals(List.of(7, 8), p.shards());
            assertEquals(Duration.ofMillis(1500), p.timeout());
            assertEquals(List.of("old-key-0123", " new-key-0123"), p.webhookKeys().keys(), "keys are never trimmed");
            assertTrue(p.webhookKeys().contains(" new-key-0123"));
            assertFalse(p.webhookKeys().contains("new-key-0123"));
            assertFalse(p.toString().contains("key-0123"), p.toString());
            assertFalse(p.webhookKeys().toString().contains("key-0123"));
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
        assertFalse(e.getMessage().contains("\n"), "the exception message is one line: " + e.getMessage());
        assertFalse(all.contains(ShopFixture.DB_SECRET), all);
        assertFalse(all.contains(ShopFixture.KS_PASSWORD), all);
        assertTrue(all.contains("[invalid_type] SHOP_PORT: is not a valid integer (got \"eighty\")"), all);

        String log = Files.readString(tmp.resolve("termination-log"));
        assertTrue(log.contains("[certificate_expiring] tls"), log);
        assertFalse(log.contains(ShopFixture.DB_SECRET));

        FailureAnalysis analysis = new DocuconfFailureAnalyzer().analyze(new IllegalStateException(e));
        assertTrue(analysis.getDescription().startsWith("docuconf: 12 configuration problems:\n"),
                analysis.getDescription());
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
    void lengthLimitsCountCodePoints() throws Exception {
        shop.env.put("SHOP_BRANCHES", "ZÜ01,日本,\uD83D\uDE80\uD83D\uDE80");
        shop.env.put("SHOP_LIMITS", "{\"perMinute\":60,\"burst\":10}");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            assertEquals(List.of("ZÜ01", "日本", "\uD83D\uDE80\uD83D\uDE80"), ctx.getBean(ShopProperties.class).branches());
        }
        shop.env.put("SHOP_BRANCHES", "BE,ZÜRICH");
        DocuconfValidationException e = fails();
        assertEquals(List.of("out_of_range SHOP_BRANCHES"), codes(e));
        assertTrue(e.getViolations().get(0).message().contains("6 characters, above itemMaxLength 4"),
                e.getViolations().toString());
        shop.env.put("SHOP_BRANCHES", "BE");
        // A json value is measured as received, whitespace included.
        shop.env.put("SHOP_LIMITS", "{ \"perMinute\": 60, \"burst\": 10 }");
        assertEquals(List.of("out_of_range SHOP_LIMITS"), codes(fails()));
    }

    @Test
    void nestedJsonIsMeasuredAsCompactJson() {
        shop.env.put("SHOP_LIMITS_PERMINUTE", "123456789");
        shop.env.put("SHOP_LIMITS_BURST", "123456789");
        DocuconfValidationException e = fails();
        assertEquals(List.of("out_of_range SHOP_LIMITS"), codes(e));
        assertTrue(e.getViolations().get(0).message().contains("41 characters of JSON, above maxLength 30"),
                e.getViolations().toString());
    }

    @Test
    void indexedListItemsFromTheEnvironment() throws Exception {
        // Spring's relaxed binding reads SHOP_SHARDS_0 and SHOP_SHARDS__0 as list items; SHOP_SHARDS_HOST is not one.
        shop.env.put("SHOP_SHARDS_0", "5");
        shop.env.put("SHOP_SHARDS__1", "6");
        shop.env.put("SHOP_SHARDS_HOST", "x");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            assertEquals(List.of(5, 6), ctx.getBean(ShopProperties.class).shards());
        }
        // SPEC §5: a gap is invalid_type.
        shop.env.remove("SHOP_SHARDS__1");
        shop.env.put("SHOP_SHARDS_2", "7");
        DocuconfValidationException e = fails();
        assertEquals(List.of("invalid_type SHOP_SHARDS"), codes(e));
        assertTrue(e.getViolations().get(0).message().contains("SHOP_SHARDS_1 is not"), e.getViolations().toString());
        // So is a list that does not start at 0, and an index with a leading zero, which Spring cannot bind.
        shop.env.remove("SHOP_SHARDS_0");
        shop.env.remove("SHOP_SHARDS_2");
        shop.env.put("SHOP_SHARDS_1", "6");
        assertEquals(List.of("invalid_type SHOP_SHARDS"), codes(fails()));
        shop.env.remove("SHOP_SHARDS_1");
        shop.env.put("SHOP_SHARDS_00", "6");
        assertEquals(List.of("invalid_type SHOP_SHARDS"), codes(fails()));
        // The comma-separated form wins over items, as Spring reads it.
        shop.env.put("SHOP_SHARDS", "1,2");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            assertEquals(List.of(1, 2), ctx.getBean(ShopProperties.class).shards());
        }
    }

    @Test
    void integersTheTypeCannotHoldAreOutOfRange() {
        shop.env.put("SHOP_PORT", "99999999999");
        shop.env.put("SHOP_SHARDS", "1,99999999999999999999");
        DocuconfValidationException e = fails();
        assertEquals(List.of("out_of_range SHOP_PORT", "out_of_range SHOP_SHARDS"), codes(e));
        assertTrue(e.getViolations().toString().contains("SHOP_PORT: is outside the range of int"),
                e.getViolations().toString());
    }

    @Test
    void enumValuesBindInAnyCaseAsSpringDoes() {
        // The platform checks the contract's spelling; the app accepts what Spring accepts, wherever it comes from.
        shop.env.put("SHOP_LEVEL", "warn");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            assertEquals(ShopProperties.Level.WARN, ctx.getBean(ShopProperties.class).level());
        }
        shop.env.put("SHOP_LEVEL", "loud");
        assertEquals(List.of("not_in_enum SHOP_LEVEL"), codes(fails()));
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
    void aWatchedKeystoreReloadsWithTheBootPasswordAndReportsItsStatus() throws Exception {
        try (ConfigurableApplicationContext ctx = shop.run()) {
            DocuconfFiles files = ctx.getBean(DocuconfFiles.class);
            ReloadRecorder recorder = ctx.getBean(ReloadRecorder.class);
            assertEquals(new ReloadStatus(1, null, null), files.reloadStatus("partner"));
            Keystore before = files.get("partner", Keystore.class).orElseThrow();
            List<Keystore> hooked = new CopyOnWriteArrayList<>();
            files.onChange("partner", Keystore.class, k -> {
                throw new IllegalStateException("hook failed");
            });
            files.onChange("partner", Keystore.class, hooked::add);

            // A property changed after startup, as a watched overlay could, is not the password a reload uses: the
            // process's environment does not change, so a reload re-opens the keystore with the boot password.
            String newPassword = "new-pa55word";
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("later",
                    Map.of("SHOP_KEYSTOREPASSWORD", newPassword, "shop.keystore-password", newPassword)));
            shop.write("etc/shop/partner/keystore.p12",
                    TestCerts.keystore("PKCS12", TestCerts.selfSigned(TestCerts.rsa(), 30), newPassword));
            ReloadStatus rejected = awaitStatus(files, "partner", s -> s.lastRejected() != null);
            assertEquals(1, rejected.generation());
            assertNull(rejected.lastReload());
            assertEquals("partner", rejected.lastRejected().input());
            assertEquals(List.of("keystore_unreadable"), rejected.lastRejected().codes());
            assertSame(before, files.get("partner", Keystore.class).orElseThrow(), "the previous value is kept");
            assertEquals(List.of(), hooked, "no hook runs for a rejected change");
            assertEquals(List.of(), recorder.events, "no event for a rejected change");

            // A new keystore with the boot password is accepted: the throwing hook and the throwing listener are
            // logged, and the others still run.
            shop.write("etc/shop/partner/keystore.p12", TestCerts.keystore("PKCS12",
                    TestCerts.selfSigned(TestCerts.rsa(), 60), ShopFixture.KS_PASSWORD));
            ReloadStatus accepted = awaitStatus(files, "partner", s -> s.generation() == 2);
            assertNull(accepted.lastRejected(), "an accepted reload clears the rejection");
            assertNotNull(accepted.lastReload());
            assertEquals(1, hooked.size());
            assertSame(hooked.get(0), files.get("partner", Keystore.class).orElseThrow());
            assertNotNull(hooked.get(0).load(ShopFixture.KS_PASSWORD.toCharArray()));
            awaitTrue(() -> recorder.events.size() == 1);
            assertEquals("partner", recorder.events.get(0).getInput());
            assertEquals(2, recorder.events.get(0).getGeneration());
            assertSame(hooked.get(0), recorder.events.get(0).getValue(Keystore.class));
        }
    }

    private static ReloadStatus awaitStatus(DocuconfFiles files, String input, Predicate<ReloadStatus> done)
            throws InterruptedException {
        awaitTrue(() -> done.test(files.reloadStatus(input)));
        return files.reloadStatus(input);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for the reload");
            Thread.sleep(50);
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
        assertFalse(e.getMessage().contains("\n"), "the exception message is one line: " + e.getMessage());
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
        assertTrue(vals.getViolations().toString().contains("holds an unresolved ref+ reference"),
                vals.getViolations().toString());
        assertFalse(vals.getViolations().toString().contains("shop/db"), vals.getViolations().toString());
    }

    @Test
    void referencesInNonSecretVariablesAreLeftToTheirConstraints() {
        shop.env.put("SHOP_CONTACT", "vault:ops");
        DocuconfValidationException e = fails();
        assertEquals(List.of("invalid_type SHOP_CONTACT"), codes(e));
        assertTrue(e.getViolations().toString().contains("(@Email)"), e.getViolations().toString());
    }
}
