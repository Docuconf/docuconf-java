package dev.docuconf.examples.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.check.ContractFirst;
import dev.docuconf.check.Violation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shop example's test matrix (see README.md): roles, requiredIf and the included otel@1 fragment, each loaded
 * against the committed contract.json from a base environment and a new, empty file root.
 */
class ShopConfigTest {

    private static final String DB = "postgres://shop@db:5432/shop";

    /** Where the contract puts the webhook signing key, under the file root. */
    private static final String KEY_PATH = "/etc/shop/webhooks/signing.key";

    @TempDir
    Path root;

    private ContractFirst.Result load(String... pairs) throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("DATABASE_URL", DB);
        env.put("DOCUCONF_FILE_ROOT", root.toString());
        for (int i = 0; i < pairs.length; i += 2) {
            env.put(pairs[i], pairs[i + 1]);
        }
        return ContractFirst.load(Files.readString(Path.of("contract.json"), StandardCharsets.UTF_8), env);
    }

    private static void assertOk(ContractFirst.Result r, String line) {
        assertTrue(r.ok(), r.violations().toString());
        assertEquals(line, ShopApplication.describe(r.values()));
    }

    /** Asserts there is exactly one violation, with this code and input, and returns it. */
    private static Violation assertOnly(ContractFirst.Result r, String code, String input) {
        assertEquals(1, r.violations().size(), r.violations().toString());
        Violation v = r.violations().get(0);
        assertEquals(code, v.code().id(), v.toString());
        assertEquals(input, v.input(), v.toString());
        return v;
    }

    @Test
    void case01WebWithOrigins() throws IOException {
        assertOk(load("DOCUCONF_ROLE", "web", "ALLOWED_ORIGINS", "https://shop.example"),
                "shop web: port 8080, origins https://shop.example");
    }

    @Test
    void case02WorkerNeedsNoOrigins() throws IOException {
        assertOk(load("DOCUCONF_ROLE", "worker", "QUEUES", "default,mail"),
                "shop worker: queues default,mail, webhooks off");
    }

    @Test
    void case03WebWithoutOrigins() throws IOException {
        assertOnly(load("DOCUCONF_ROLE", "web"), "missing_required", "ALLOWED_ORIGINS");
    }

    @Test
    void case04NoRoleIsTheOnlyProblem() throws IOException {
        assertOnly(load("QUEUES", "default"), "missing_required", "DOCUCONF_ROLE");
    }

    @Test
    void case05UnknownRoleIsTheOnlyProblem() throws IOException {
        assertOnly(load("DOCUCONF_ROLE", "cron"), "not_in_enum", "DOCUCONF_ROLE");
    }

    @Test
    void case06JsonLogsNeedASchema() throws IOException {
        Violation v = assertOnly(load("DOCUCONF_ROLE", "worker", "QUEUES", "default", "LOG_FORMAT", "json"),
                "missing_required", "LOG_SCHEMA_URL");
        assertTrue(v.message().contains("is required when LOG_FORMAT is \"json\""), v.toString());
    }

    @Test
    void case07JsonLogsWithASchema() throws IOException {
        assertOk(load("DOCUCONF_ROLE", "worker", "QUEUES", "default", "LOG_FORMAT", "json",
                "LOG_SCHEMA_URL", "https://logs.example/v1.json"), "shop worker: queues default, webhooks off");
    }

    @Test
    void case08WebhooksNeedTheKeyFile() throws IOException {
        Violation v = assertOnly(load("DOCUCONF_ROLE", "worker", "QUEUES", "default", "WEBHOOKS_ENABLED", "true"),
                "file_missing", "webhook-signing-key");
        assertTrue(v.message().contains(", and is required when WEBHOOKS_ENABLED is true"), v.toString());
    }

    @Test
    void case09WebhooksWithTheKeyFile() throws IOException {
        String key = "0123456789abcdef0123456789abcdef-signing";
        Path file = root.resolve(KEY_PATH.substring(1));
        Files.createDirectories(file.getParent());
        Files.writeString(file, key, StandardCharsets.UTF_8);
        ContractFirst.Result r = load("DOCUCONF_ROLE", "worker", "QUEUES", "default", "WEBHOOKS_ENABLED", "true");
        assertOk(r, "shop worker: queues default, webhooks on");
        assertFalse(ShopApplication.describe(r.values()).contains(key));
    }

    @Test
    void case10TheKeyFileBelongsToTheWorker() throws IOException {
        assertOk(load("DOCUCONF_ROLE", "web", "ALLOWED_ORIGINS", "https://shop.example", "WEBHOOKS_ENABLED", "true"),
                "shop web: port 8080, origins https://shop.example");
    }

    @Test
    void case11FragmentVarsAreChecked() throws IOException {
        assertOnly(load("DOCUCONF_ROLE", "web", "ALLOWED_ORIGINS", "https://shop.example",
                "OTEL_EXPORTER_OTLP_PROTOCOL", "carrier-pigeon"), "not_in_enum", "OTEL_EXPORTER_OTLP_PROTOCOL");
    }

    @Test
    void theQueuesAreTypedValues() throws IOException {
        ContractFirst.Result r = load("DOCUCONF_ROLE", "worker", "QUEUES", "default,mail");
        assertEquals(List.of("default", "mail"), r.values().get("QUEUES"));
        assertEquals(8080L, r.values().get("PORT"));
    }

    @Test
    void secretsAreNeverInTheReport() throws IOException {
        ContractFirst.Result r = load("DOCUCONF_ROLE", "web", "DATABASE_URL", "mysql://shop:hunter2-secret@db/shop");
        assertFalse(r.ok());
        assertFalse(ContractFirst.report(r.violations()).contains("hunter2-secret"), r.violations().toString());
    }
}
