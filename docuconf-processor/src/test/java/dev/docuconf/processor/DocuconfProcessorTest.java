package dev.docuconf.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.VarSpec;
import dev.docuconf.testing.CueVet;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocuconfProcessorTest {

    @TempDir
    Path tmp;

    private static final String IMPORTS = """
            package demo;

            import dev.docuconf.*;
            import jakarta.validation.constraints.*;
            import java.net.URI;
            import java.nio.file.Path;
            import java.time.Duration;
            import java.util.List;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.boot.context.properties.bind.DefaultValue;
            import org.springframework.validation.annotation.Validated;
            """;

    private static final String RECORD = IMPORTS + """
            /**
             * Billing settings.
             *
             * @param databaseUrl Primary Postgres connection string
             * @param port HTTP listen port
             * @param timeout Upstream request timeout
             * @param origins Origins allowed to call the API
             * @param level Minimum log level
             * @param ratio Trace sampling ratio
             * @param region Cloud region, two letters and a digit
             * @param tracing Whether to trace requests
             * @param rates Pricing tiers by monthly volume
             */
            @Docuconf(service = "billing-api")
            @ConfigurationProperties("billing")
            public record BillingProperties(
                    @NotNull @Secret @UrlSchemes({"postgres", "postgresql"}) URI databaseUrl,
                    @Min(1) @Max(65535) @DefaultValue("8080") int port,
                    @DefaultValue("30s") Duration timeout,
                    @NotEmpty @Size(max = 5) List<String> origins,
                    @DefaultValue("info") Level level,
                    @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.25") double ratio,
                    @Pattern(regexp = "[a-z]{2}[0-9]") String region,
                    @DefaultValue("true") boolean tracing,
                    @NotNull @ConfigFile("/etc/billing/rates/rates.yaml") Rates rates) {

                @Override
                public String toString() {
                    return Redacted.toString(this);
                }

                public enum Level { DEBUG, INFO, WARN }

                /** Pricing tiers. */
                public record Rates(@NotEmpty List<Tier> tiers) {}

                /**
                 * One tier.
                 *
                 * @param upTo Monthly volume this tier applies up to
                 * @param price Price per unit
                 */
                public record Tier(@Positive long upTo, @DecimalMin("0") double price) {}
            }
            """;

    private static ContractBundle bundle(Compilation c) throws Exception {
        assertTrue(c.success, c.allErrors());
        return ContractJson.read(c.contractJson());
    }

    @Test
    void recordWithEveryCommonConstraint() throws Exception {
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.BillingProperties", RECORD),
                "-Adocuconf.appVersion=1.2.3");
        ContractBundle b = bundle(c);
        Contract k = b.contract();
        assertEquals("billing-api", k.name);
        assertEquals("1.2.3", k.appVersion);
        assertEquals(List.of("BILLING_DATABASEURL", "BILLING_LEVEL", "BILLING_ORIGINS", "BILLING_PORT",
                "BILLING_RATIO", "BILLING_REGION", "BILLING_TIMEOUT", "BILLING_TRACING"), List.copyOf(k.vars.keySet()));

        VarSpec db = k.vars.get("BILLING_DATABASEURL");
        assertEquals("url", db.type.id());
        assertTrue(db.required);
        assertTrue(db.secret);
        assertEquals(List.of("postgres", "postgresql"), db.schemes);
        assertEquals("Primary Postgres connection string", db.description);
        assertEquals("billing.database-url", db.configKey);

        VarSpec port = k.vars.get("BILLING_PORT");
        assertEquals(1L, port.min);
        assertEquals(65535L, port.max);
        assertEquals(8080L, port.defaultValue);
        assertFalse(port.required);

        VarSpec timeout = k.vars.get("BILLING_TIMEOUT");
        assertEquals("iso8601", timeout.encoding);
        assertEquals("30s", timeout.defaultValue);

        VarSpec origins = k.vars.get("BILLING_ORIGINS");
        assertEquals("list", origins.type.id());
        assertEquals("csv", origins.encoding);
        assertEquals(1, origins.minItems);
        assertEquals(5, origins.maxItems);
        assertTrue(origins.required);

        assertEquals(List.of("DEBUG", "INFO", "WARN"), k.vars.get("BILLING_LEVEL").values);
        assertEquals("INFO", k.vars.get("BILLING_LEVEL").defaultValue);
        assertEquals(new BigDecimal("0.25"), k.vars.get("BILLING_RATIO").defaultValue);
        assertEquals("^(?:[a-z]{2}[0-9])$", k.vars.get("BILLING_REGION").pattern);
        assertEquals(true, k.vars.get("BILLING_TRACING").defaultValue);

        FileSpec rates = k.files.get("rates");
        assertEquals("config", rates.type.id());
        assertEquals("yaml", rates.format);
        assertTrue(rates.required);
        assertEquals("Pricing tiers by monthly volume", rates.description);
        String schema = dev.docuconf.contract.Json.write(rates.schema);
        assertTrue(schema.contains("\"required\":[\"tiers\"]"), schema);
        assertTrue(schema.contains("\"upTo\":{\"type\":\"integer\",\"description\":\"Monthly volume this tier applies"
                + " up to\",\"exclusiveMinimum\":0}"), schema);
        assertTrue(schema.contains("\"additionalProperties\":false"), schema);

        assertEquals("demo.BillingProperties", b.bindings().classes.get(0).className());
        assertEquals("billing", b.bindings().classes.get(0).prefix());
        assertEquals("databaseUrl", b.bindings().vars.get("BILLING_DATABASEURL").javaPath());
        assertEquals("rates", b.bindings().files.get("rates").javaPath());

        CueVet.Result vet = CueVet.vet(c.contractCue(), tmp.resolve("vet"));
        assertEquals(0, vet.exitCode(), vet.output() + "\n" + c.contractCue());
    }

    private static final String BEAN = IMPORTS + """
            @Docuconf
            @Validated
            @ConfigurationProperties("orders")
            public class OrdersProperties {
                /** Checkout request timeout. */
                @DurationMinHolder
                private Duration checkoutTimeout = Duration.ofSeconds(90);

                /** Warehouse API base URL. */
                @NotNull
                @UrlSchemes("https")
                private String warehouseApi;

                /** Maximum items per order. */
                @Max(100)
                private int maxItems = 20;

                /** Queue names to consume. */
                private List<String> queues = List.of("orders", "refunds");

                /** Nested database settings. */
                private final Db db = new Db();

                /** The {@code <b>shared</b>} cache size. */
                private Integer cacheSize;

                @External("vault")
                private String vaultToken;

                public Duration getCheckoutTimeout() { return checkoutTimeout; }
                public void setCheckoutTimeout(Duration d) { this.checkoutTimeout = d; }
                public String getWarehouseApi() { return warehouseApi; }
                public void setWarehouseApi(String s) { this.warehouseApi = s; }
                public int getMaxItems() { return maxItems; }
                public void setMaxItems(int n) { this.maxItems = n; }
                public List<String> getQueues() { return queues; }
                public void setQueues(List<String> q) { this.queues = q; }
                public Db getDb() { return db; }
                public Integer getCacheSize() { return cacheSize; }
                public void setCacheSize(Integer n) { this.cacheSize = n; }
                public String getVaultToken() { return vaultToken; }
                public void setVaultToken(String s) { this.vaultToken = s; }

                public static class Db {
                    /** Connection pool size. */
                    @Min(1)
                    private int poolSize = 10;
                    public int getPoolSize() { return poolSize; }
                    public void setPoolSize(int n) { this.poolSize = n; }
                }
            }

            @interface DurationMinHolder {}
            """;

    @Test
    void javaBeanWithApplicationYamlAndProfiles() throws Exception {
        Map<String, String> resources = Map.of(
                "application.yml", """
                        spring:
                          application:
                            name: orders-api
                        orders:
                          max-items: 50
                          warehouseApi: https://warehouse.internal
                        ---
                        spring.config.activate.on-profile: staging
                        orders.db.pool-size: 2
                        """,
                "application-prod.properties", "orders.max-items=80\norders.queues=orders,refunds,audit\n",
                "application-dev.yml", "orders:\n  checkout-timeout: 5m\n  cache-size: 64\n");
        Compilation c = Compilation.compile(tmp, resources, Map.of("demo.OrdersProperties", BEAN));
        ContractBundle b = bundle(c);
        Contract k = b.contract();
        assertEquals("orders-api", k.name);
        assertEquals(List.of("ORDERS_CACHESIZE", "ORDERS_CHECKOUTTIMEOUT", "ORDERS_DB_POOLSIZE", "ORDERS_MAXITEMS",
                "ORDERS_QUEUES", "ORDERS_WAREHOUSEAPI", "SPRING_PROFILES_ACTIVE"), List.copyOf(k.vars.keySet()));
        assertEquals("1m30s", k.vars.get("ORDERS_CHECKOUTTIMEOUT").defaultValue);
        assertEquals("Checkout request timeout.", k.vars.get("ORDERS_CHECKOUTTIMEOUT").description);
        assertEquals("The shared cache size.", k.vars.get("ORDERS_CACHESIZE").description);
        // application.yml overrides the field initializer, and makes a @NotNull property optional.
        assertEquals(50L, k.vars.get("ORDERS_MAXITEMS").defaultValue);
        VarSpec api = k.vars.get("ORDERS_WAREHOUSEAPI");
        assertEquals("url", api.type.id());
        assertFalse(api.required);
        assertEquals("https://warehouse.internal", api.defaultValue);
        assertEquals(List.of("orders", "refunds"), k.vars.get("ORDERS_QUEUES").defaultValue);
        assertEquals(10L, k.vars.get("ORDERS_DB_POOLSIZE").defaultValue);
        assertEquals("orders.db.pool-size", k.vars.get("ORDERS_DB_POOLSIZE").configKey);
        assertNull(k.vars.get("ORDERS_CACHESIZE").defaultValue);

        assertEquals("SPRING_PROFILES_ACTIVE", k.profiles.selector);
        assertEquals("default", k.profiles.defaultProfile);
        // SPEC §4.4: the selector the SDK adds defaults to profiles.default.
        assertEquals("default", k.vars.get("SPRING_PROFILES_ACTIVE").defaultValue);
        assertEquals(Map.of("ORDERS_MAXITEMS", 80L, "ORDERS_QUEUES", List.of("orders", "refunds", "audit")),
                k.profiles.defaults.get("prod"));
        assertEquals(Map.of("ORDERS_DB_POOLSIZE", 2L), k.profiles.defaults.get("staging"));
        assertEquals(Map.of("ORDERS_CHECKOUTTIMEOUT", "5m", "ORDERS_CACHESIZE", 64L), k.profiles.defaults.get("dev"));

        CueVet.Result vet = CueVet.vet(c.contractCue(), tmp.resolve("vet"));
        assertEquals(0, vet.exitCode(), vet.output() + "\n" + c.contractCue());
    }

    @Test
    void itemBoundsAndNarrowIntegerTypesAreExported() throws Exception {
        String record = IMPORTS + """
                /**
                 * Sharding.
                 *
                 * @param shards Shard ids this instance owns
                 * @param weights Relative shard weights
                 * @param offsets Offsets in bytes
                 * @param retries Retries per request
                 * @param level Compression level
                 * @param budget Byte budget
                 */
                @Docuconf(service = "shards")
                @ConfigurationProperties("shards")
                public record ShardProperties(
                        @Size(max = 64) List<@Min(0) @Max(1023) Integer> shards,
                        List<@Positive Short> weights,
                        List<Long> offsets,
                        @Min(0) int retries,
                        byte level,
                        long budget) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.ShardProperties", record));
        Contract k = bundle(c).contract();
        VarSpec shards = k.vars.get("SHARDS_SHARDS");
        assertEquals(0L, shards.itemMin);
        assertEquals(1023L, shards.itemMax);
        assertEquals(64, shards.maxItems);
        VarSpec weights = k.vars.get("SHARDS_WEIGHTS");
        assertEquals(1L, weights.itemMin);
        assertEquals((long) Short.MAX_VALUE, weights.itemMax);
        VarSpec offsets = k.vars.get("SHARDS_OFFSETS");
        assertNull(offsets.itemMin);
        assertNull(offsets.itemMax);
        VarSpec retries = k.vars.get("SHARDS_RETRIES");
        assertEquals(0L, retries.min);
        assertEquals((long) Integer.MAX_VALUE, retries.max);
        VarSpec level = k.vars.get("SHARDS_LEVEL");
        assertEquals((long) Byte.MIN_VALUE, level.min);
        assertEquals((long) Byte.MAX_VALUE, level.max);
        VarSpec budget = k.vars.get("SHARDS_BUDGET");
        assertNull(budget.min);
        assertNull(budget.max);
        assertTrue(c.contractCue().contains("itemMin: 0"), c.contractCue());

        CueVet.Result vet = CueVet.vet(c.contractCue(), tmp.resolve("vet"));
        assertEquals(0, vet.exitCode(), vet.output() + "\n" + c.contractCue());
    }

    @Test
    void lengthLimitsAreExported() throws Exception {
        String record = IMPORTS + """
                /**
                 * Batch settings.
                 *
                 * @param callback Where to report each run
                 * @param hook Webhook as a string
                 * @param limits Run limits as a JSON object
                 * @param branches Branch codes, two to four characters each
                 */
                @Docuconf(service = "batch")
                @ConfigurationProperties("batch")
                public record BatchProperties(
                        @UrlSchemes("https") @MaxLength(24) URI callback,
                        @UrlSchemes("https") @Size(max = 64) String hook,
                        @Json @MaxLength(16) Limits limits,
                        @DefaultValue({"ZÜ01", "BE"}) List<@Size(min = 2, max = 4) String> branches) {
                    public record Limits(int max) {}
                }
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.BatchProperties", record));
        assertTrue(c.success, c.allErrors());
        Contract k = bundle(c).contract();
        assertEquals(24, k.vars.get("BATCH_CALLBACK").maxLength);
        assertEquals(64, k.vars.get("BATCH_HOOK").maxLength);
        assertEquals(16, k.vars.get("BATCH_LIMITS").maxLength);
        VarSpec branches = k.vars.get("BATCH_BRANCHES");
        assertEquals(2, branches.itemMinLength);
        assertEquals(4, branches.itemMaxLength);
        assertTrue(c.contractCue().contains("itemMaxLength: 4"), c.contractCue());

        CueVet.Result vet = CueVet.vet(c.contractCue(), tmp.resolve("vet"));
        assertEquals(0, vet.exitCode(), vet.output() + "\n" + c.contractCue());
    }

    @Test
    void lengthLimitDeclarationErrorsFailTheBuild() throws Exception {
        String record = IMPORTS + """
                /**
                 * Bad lengths.
                 *
                 * @param port Listen port
                 * @param ports Worker ports
                 * @param codes Branch codes
                 * @param hook Callback URL
                 */
                @Docuconf(service = "badlen")
                @ConfigurationProperties("badlen")
                public record BadLengths(
                        @MaxLength(5) int port,
                        List<@Size(max = 4) Integer> ports,
                        @DefaultValue({"BE", "ZÜRICH"}) List<@Size(max = 4) String> codes,
                        @MaxLength(10) @DefaultValue("https://a.example/long") URI hook) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.BadLengths", record));
        assertFalse(c.success);
        String errors = c.allErrors();
        assertTrue(errors.contains("BADLEN_PORT: @MaxLength applies to url and @Json properties"), errors);
        assertTrue(errors.contains("BADLEN_PORTS: @Size on the items of an int list has no contract form"), errors);
        assertTrue(errors.contains("BADLEN_CODES: default item 1 is 6 characters, above itemMaxLength 4"), errors);
        assertTrue(errors.contains("BADLEN_HOOK: default is 22 characters, above maxLength 10"), errors);
    }

    @Test
    void itemBoundsOnAJavaBeanGetterAndADefaultOutOfBounds() throws Exception {
        String bean = IMPORTS + """
                @Docuconf
                @ConfigurationProperties("pool")
                public class PoolProperties {
                    /** Ports to probe. */
                    private List<Integer> ports = List.of(80, 70000);

                    public List<@Min(1) @Max(65535) Integer> getPorts() { return ports; }
                    public void setPorts(List<Integer> p) { this.ports = p; }
                }
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.PoolProperties", bean));
        assertFalse(c.success);
        assertTrue(c.allErrors().contains("POOL_PORTS") && c.allErrors().contains("itemMax 65535"),
                c.allErrors());
    }

    @Test
    void declarationErrorsFailTheBuild() throws Exception {
        String source = IMPORTS + """
                @Docuconf(service = "bad")
                @ConfigurationProperties("bad")
                public record BadProperties(
                        @Min(1) @Max(10) @DefaultValue("20") int retries,
                        @Description("API token") @Secret @DefaultValue("abc") String token,
                        @Description("Identifier") @Pattern(regexp = "(?=a)\\\\w+") String id,
                        int noDescription,
                        @Description("Serving cert") @TlsFile("/etc/bad/tls") String wrongType,
                        @Description("Trusted CAs") @CaBundleFile("/etc/ssl/certs/private.pem") CaBundle ca) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.BadProperties", source));
        assertFalse(c.success);
        String errors = c.allErrors();
        assertTrue(errors.contains("BAD_RETRIES: default is above max 10"), errors);
        assertTrue(errors.contains("BAD_TOKEN: a secret cannot have a default"), errors);
        assertTrue(errors.contains("BAD_ID: @Pattern(\"(?=a)\\w+\") uses lookahead"), errors);
        assertTrue(errors.contains("BAD_NODESCRIPTION: needs a description"), errors);
        assertTrue(errors.contains("@TlsFile needs a property of type dev.docuconf.TlsKeyPair"), errors);
        assertTrue(errors.contains("ca: would be mounted at /etc/ssl/certs"), errors);
        assertNull(c.contractCue());
    }

    @Test
    void secretsInConfigFilesFailTheBuild() throws Exception {
        String source = IMPORTS + """
                @Docuconf(service = "svc")
                @ConfigurationProperties("svc")
                public record SvcProperties(@Description("API token") @Secret String token) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of("application-prod.yml", "svc.token: hunter2\n"),
                Map.of("demo.SvcProperties", source));
        assertFalse(c.success);
        assertTrue(c.allErrors().contains("SVC_TOKEN: a secret cannot have a value in application-prod.yml"),
                c.allErrors());
        assertFalse(c.allErrors().contains("hunter2"));
    }

    @Test
    void featureFlagNamesWarn() throws Exception {
        String source = IMPORTS + """
                @Docuconf(service = "svc")
                @ConfigurationProperties("enable")
                public record SvcProperties(@Description("New checkout flow") boolean checkout) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.SvcProperties", source));
        assertTrue(c.success, c.allErrors());
        assertTrue(c.warnings.stream().anyMatch(w -> w.contains("ENABLE_CHECKOUT: looks like a feature flag")),
                c.warnings.toString());
    }

    @Test
    void profileSelectorDefaultsToTheDefaultProfile() throws Exception {
        String source = IMPORTS + """
                @Docuconf(service = "svc")
                @ConfigurationProperties("svc")
                public record SvcProperties(@Description("Worker count") @DefaultValue("1") int workers) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(
                "application.yml", "spring.profiles.default: prod\n",
                "application-prod.yml", "svc.workers: 4\n"), Map.of("demo.SvcProperties", source));
        Contract k = bundle(c).contract();
        assertEquals("prod", k.profiles.defaultProfile);
        assertEquals("prod", k.vars.get("SPRING_PROFILES_ACTIVE").defaultValue);
        assertTrue(c.contractCue().contains("""
                		SPRING_PROFILES_ACTIVE: {
                			type: "string"
                			description: "Active Spring profile; selects the application-{profile}.yml baked into the image"
                			configKey: "spring.profiles.active"
                			pattern: "^[^,]+$"
                			default: "prod"
                		}
                """), c.contractCue());
        CueVet.Result vet = CueVet.vet(c.contractCue(), tmp.resolve("vet"));
        assertEquals(0, vet.exitCode(), vet.output() + "\n" + c.contractCue());
    }

    @Test
    void overlaysAreExportedAndPassTheMetaSchema() throws Exception {
        String source = BEAN.replace("@Docuconf\n", """
                @Docuconf(service = "orders-api")
                @ConfigOverlay(value = "/etc/orders/overlay/orders.yaml", reload = Reload.WATCH,
                        description = "Platform overrides, layered over application.yml")
                @ConfigOverlay(name = "tuning", value = "/etc/orders/tuning/tuning.yml")
                """);
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.OrdersProperties", source));
        Contract k = bundle(c).contract();
        assertEquals(List.of("platform", "tuning"), List.copyOf(k.overlays.keySet()));
        assertEquals("watch", k.overlays.get("platform").reload);
        assertEquals("restart", k.overlays.get("tuning").reload);
        assertTrue(c.contractCue().contains("""
                	overlays: {
                		platform: {
                			description: "Platform overrides, layered over application.yml"
                			format: "yaml"
                			path: "/etc/orders/overlay/orders.yaml"
                			keySeparator: "."
                			reload: "watch"
                		}
                		tuning: {
                			format: "yaml"
                			path: "/etc/orders/tuning/tuning.yml"
                			keySeparator: "."
                		}
                	}
                """), c.contractCue());
        // Every variable carries the key an overlay writes it at.
        assertEquals("orders.checkout-timeout", k.vars.get("ORDERS_CHECKOUTTIMEOUT").configKey);
        CueVet.Result vet = CueVet.vet(c.contractCue(), tmp.resolve("vet"));
        assertEquals(0, vet.exitCode(), vet.output() + "\n" + c.contractCue());
    }

    @Test
    void badOverlaysFailTheBuild() throws Exception {
        String source = IMPORTS + """
                /**
                 * Settings.
                 *
                 * @param workers Worker count
                 * @param token API token
                 * @param rates Pricing tiers
                 */
                @Docuconf(service = "svc")
                @ConfigOverlay(value = "/etc/svc/overlay/svc.json", reload = Reload.WATCH)
                @ConfigOverlay(name = "Bad_Name", value = "/etc/svc/rates/overlay.yaml", description = "tiny")
                @ConfigOverlay(name = "root", value = "/app/overlay.yaml")
                @ConfigurationProperties("svc")
                public record SvcProperties(@DefaultValue("1") int workers, @Secret String token,
                        @ConfigFile("/etc/svc/rates/rates.yaml") Rates rates) {
                    /** Tiers. */
                    public record Rates(List<String> tiers) {}
                }
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.SvcProperties", source));
        assertFalse(c.success);
        String errors = c.allErrors();
        assertTrue(errors.contains("@ConfigOverlay(name = \"platform\"): path /etc/svc/overlay/svc.json must end in"
                + " .yml or .yaml"), errors);
        assertTrue(errors.contains("@ConfigOverlay(name = \"platform\", reload = WATCH): demo.SvcProperties is bound"
                + " through its constructor"), errors);
        assertTrue(errors.contains("overlay Bad_Name: overlay names must be DNS labels"), errors);
        assertTrue(errors.contains("overlay Bad_Name: description \"tiny\" is shorter than 5 characters"), errors);
        assertTrue(errors.contains("overlay Bad_Name: shares the mount directory /etc/svc/rates with rates"), errors);
        assertTrue(errors.contains("overlay root: would be mounted at /app"), errors);
        assertNull(c.contractCue());
    }

    @Test
    void watchIsAllowedWhenARecordHoldsOnlySecretsAndFiles() throws Exception {
        String source = IMPORTS + """
                /**
                 * Settings.
                 *
                 * @param token API token
                 * @param license Licence key
                 */
                @Docuconf(service = "svc")
                @ConfigOverlay(value = "/etc/svc/overlay/svc.yaml", reload = Reload.WATCH)
                @ConfigurationProperties("svc")
                public record SvcProperties(@Secret String token, @TextFile("/etc/svc/license/key") String license) {
                    @Override
                    public String toString() {
                        return Redacted.toString(this);
                    }
                }
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.SvcProperties", source));
        assertEquals("watch", bundle(c).contract().overlays.get("platform").reload);
    }
}
