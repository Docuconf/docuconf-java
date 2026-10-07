package dev.docuconf.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the processor tells the user about a declaration: positions, wording, and misplaced annotations. */
class DeclarationDiagnosticsTest {

    @TempDir
    Path tmp;

    private static final String IMPORTS = """
            package demo;

            import dev.docuconf.*;
            import jakarta.validation.constraints.*;
            import java.net.URI;
            import java.time.Duration;
            import java.util.List;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.boot.context.properties.bind.DefaultValue;
            import org.springframework.boot.convert.Delimiter;
            """;

    @Test
    void everyDiagnosticHasAFileAndLine() throws Exception {
        String source = IMPORTS + """
                /** @param token API token used upstream */
                @Docuconf(service = "bad")
                @ConfigurationProperties("bad")
                public record BadProperties(
                        @Min(1) @Max(10) @DefaultValue("20") int retries,
                        @Secret @DefaultValue("abc") String token,
                        int noDescription,
                        @Description("Worker threads") @Min(1) @Max(64) int workers,
                        @Description("Enables the beta UI") boolean enableBeta) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.BadProperties", source));
        assertFalse(c.success);
        List<Diagnostic<?>> found = c.diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING)
                .<Diagnostic<?>>map(d -> d).toList();
        assertTrue(found.size() >= 5, found.toString());
        for (Diagnostic<?> d : found) {
            assertTrue(d.getSource() != null && d.getLineNumber() > 0,
                    "no position: " + d.getMessage(Locale.ROOT));
        }
        assertTrue(c.allErrors().contains("BAD_RETRIES: default is above max 10"), c.allErrors());
        assertTrue(c.warnings.stream().anyMatch(w -> w.startsWith("BAD_ENABLEBETA: enable-beta looks like a feature"
                + " flag")), c.warnings.toString());
        assertFalse(c.allErrors().contains("§"), c.allErrors());
    }

    @Test
    void anUnsetPrimitiveExplainsSpringsZeroInsteadOfBlamingTheUser() throws Exception {
        String source = IMPORTS + """
                /** @param workerCount Worker threads to start */
                @Docuconf(service = "orders")
                @ConfigurationProperties("orders")
                public record OrdersProperties(@Min(1) @Max(64) int workerCount) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.OrdersProperties", source));
        assertFalse(c.success);
        assertEquals(List.of("ORDERS_WORKERCOUNT: `int workerCount` has no @DefaultValue, so Spring binds 0 when"
                + " ORDERS_WORKERCOUNT is unset, which breaks its minimum 1. Add @DefaultValue(\"1\"), or make it"
                + " `@NotNull Integer` to require it."), c.errors);
    }

    @Test
    void anUnsetPrimitiveWithAYamlValueIsFine() throws Exception {
        String source = IMPORTS + """
                /** @param workerCount Worker threads to start */
                @Docuconf(service = "orders")
                @ConfigurationProperties("orders")
                public record OrdersProperties(@Min(1) @Max(64) int workerCount) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of("application.yml", "orders.worker-count: 4\n"),
                Map.of("demo.OrdersProperties", source));
        assertTrue(c.success, c.allErrors());
    }

    @Test
    void annotationsThatDoNotFitTheTypeFailTheBuild() throws Exception {
        String source = IMPORTS + """
                @Docuconf(service = "svc")
                @ConfigurationProperties("svc")
                public record SvcProperties(
                        @Description("HTTP listen port") @UrlSchemes("http") @DefaultValue("8080") int port,
                        @Description("Request budget") @Size(min = 1) @DefaultValue("5") int budget,
                        @Description("Upstream timeout") @Pattern(regexp = "[0-9]+s") Duration timeout,
                        @Description("Region code") @Min(1) String region,
                        @Description("Owner name") @Delimiter(";") String owner,
                        @Description("Database settings") @Secret Db db) {

                    /** @param url Database connection string */
                    public record Db(URI url) {}
                }
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.SvcProperties", source));
        assertFalse(c.success);
        String e = c.allErrors();
        assertTrue(e.contains("SVC_PORT: @UrlSchemes applies to String, URI or URL, not int"), e);
        assertTrue(e.contains("SVC_BUDGET: @Size applies to String, a collection, a map or an array, not int"), e);
        assertTrue(e.contains("SVC_TIMEOUT: @Pattern applies to String, not Duration"), e);
        assertTrue(e.contains("SVC_REGION: @Min applies to numbers, not String"), e);
        assertTrue(e.contains("SVC_OWNER: @Delimiter applies to a list, set or array, not String"), e);
        assertTrue(e.contains("SVC_DB: @Secret applies to a single value, not to the nested class Db"), e);
    }

    @Test
    void sizeOnAJsonListReachesItsSchema() throws Exception {
        String source = IMPORTS + """
                @Docuconf(service = "svc")
                @ConfigurationProperties("svc")
                public record SvcProperties(
                        @Description("Upstream hosts as JSON") @Json @NotNull @Size(min = 1, max = 3)
                        List<String> hosts) {}
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.SvcProperties", source));
        assertTrue(c.success, c.allErrors());
        Contract contract = ContractJson.read(c.contractJson()).contract();
        Map<String, Object> schema = contract.vars.get("SVC_HOSTS").schema;
        assertEquals(1L, ((Number) schema.get("minItems")).longValue(), schema.toString());
        assertEquals(3L, ((Number) schema.get("maxItems")).longValue(), schema.toString());
    }

    @Test
    void enumValuesCanBeExportedInLowerCase() throws Exception {
        String source = IMPORTS + """
                @Docuconf(service = "svc", enumCase = EnumCase.LOWER)
                @ConfigurationProperties("svc")
                public record SvcProperties(
                        @Description("Minimum log level") @DefaultValue("INFO") Level logLevel,
                        @Description("Cache backend") @EnumValues(EnumCase.KEBAB) @DefaultValue("IN_MEMORY")
                        Backend cache,
                        @Description("Fallback backend") @EnumValues(EnumCase.AS_DECLARED) Backend fallback) {

                    public enum Level { DEBUG, INFO, WARN, ERROR }

                    public enum Backend { IN_MEMORY, REDIS }
                }
                """;
        Compilation c = Compilation.compile(tmp, Map.of(), Map.of("demo.SvcProperties", source));
        assertTrue(c.success, c.allErrors());
        Contract contract = ContractJson.read(c.contractJson()).contract();
        assertEquals(List.of("debug", "info", "warn", "error"), contract.vars.get("SVC_LOGLEVEL").values);
        assertEquals("info", contract.vars.get("SVC_LOGLEVEL").defaultValue);
        assertEquals(List.of("in-memory", "redis"), contract.vars.get("SVC_CACHE").values);
        assertEquals("in-memory", contract.vars.get("SVC_CACHE").defaultValue);
        assertEquals(List.of("IN_MEMORY", "REDIS"), contract.vars.get("SVC_FALLBACK").values);
    }

    @Test
    void underscoredNamesAreOptInAndCollisionsFail() throws Exception {
        String ok = IMPORTS + """
                @Docuconf(service = "svc", envNames = EnvNames.UNDERSCORED)
                @ConfigurationProperties("svc")
                public record SvcProperties(@Description("Origins allowed to call") List<String> allowedOrigins) {}
                """;
        Compilation c = Compilation.compile(tmp.resolve("ok"), Map.of(), Map.of("demo.SvcProperties", ok));
        assertTrue(c.success, c.allErrors());
        assertTrue(ContractJson.read(c.contractJson()).contract().vars.containsKey("SVC_ALLOWED_ORIGINS"));

        String clash = IMPORTS + """
                @Docuconf(service = "svc", envNames = EnvNames.UNDERSCORED)
                @ConfigurationProperties("svc")
                public record SvcProperties(
                        @Description("Origins allowed to call") String allowedOrigins,
                        @Description("Allowed settings") Allowed allowed) {

                    /** @param origins Origins, again */
                    public record Allowed(String origins) {}
                }
                """;
        Compilation bad = Compilation.compile(tmp.resolve("bad"), Map.of(), Map.of("demo.SvcProperties", clash));
        assertFalse(bad.success);
        assertTrue(bad.allErrors().contains("SVC_ALLOWED_ORIGINS is declared twice: svc.allowed-origins and"
                + " svc.allowed.origins both map to it"), bad.allErrors());
    }

    @Test
    void aRecordHoldingASecretMustNotUseItsGeneratedToString() throws Exception {
        String bad = IMPORTS + """
                @Docuconf(service = "svc")
                @ConfigurationProperties("svc")
                public record SvcProperties(@Description("API token") @Secret String token) {}
                """;
        Compilation c = Compilation.compile(tmp.resolve("bad"), Map.of(), Map.of("demo.SvcProperties", bad));
        assertFalse(c.success);
        assertTrue(c.allErrors().contains("SVC_TOKEN: SvcProperties is a record, and its generated toString() prints"
                + " @Secret token; override it: `@Override public String toString() { return Redacted.toString(this);"
                + " }`"), c.allErrors());

        String good = bad.replace("String token) {}", """
                String token) {
                    @Override
                    public String toString() {
                        return Redacted.toString(this);
                    }
                }""");
        Compilation ok = Compilation.compile(tmp.resolve("good"), Map.of(), Map.of("demo.SvcProperties", good));
        assertTrue(ok.success, ok.allErrors());
    }

    @Test
    void lombokToStringMustExcludeSecrets() throws Exception {
        Map<String, String> lombok = Map.of(
                "lombok.Data", "package lombok; public @interface Data {}",
                "lombok.ToString", "package lombok; public @interface ToString { @interface Exclude {} }");
        String bean = IMPORTS + """
                @lombok.Data
                @Docuconf(service = "svc")
                @ConfigurationProperties("svc")
                public class SvcProperties {
                    /** API token for the upstream */
                    @Secret EXCLUDE private String token;
                    public String getToken() { return token; }
                    public void setToken(String token) { this.token = token; }
                }
                """;
        Map<String, String> sources = new java.util.HashMap<>(lombok);
        sources.put("demo.SvcProperties", bean.replace("EXCLUDE ", ""));
        Compilation c = Compilation.compile(tmp.resolve("bad"), Map.of(), sources);
        assertFalse(c.success);
        assertTrue(c.allErrors().contains("SVC_TOKEN: Lombok's toString() for SvcProperties prints @Secret token;"
                + " mark the field @ToString.Exclude"), c.allErrors());

        sources.put("demo.SvcProperties", bean.replace("EXCLUDE", "@lombok.ToString.Exclude"));
        Compilation ok = Compilation.compile(tmp.resolve("good"), Map.of(), sources);
        assertTrue(ok.success, ok.allErrors());
    }
}
