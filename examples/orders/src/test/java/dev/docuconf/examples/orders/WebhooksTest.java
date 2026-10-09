package dev.docuconf.examples.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.docuconf.spring.DocuconfTester;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class WebhooksTest {

    private static final String DB = "postgres://orders:secret@db.internal:5432/orders";
    private static final String OLD = "o".repeat(32);
    private static final String NEW = "n".repeat(32);
    private static final byte[] BODY = "{\"order\":\"42\",\"status\":\"paid\"}".getBytes(StandardCharsets.UTF_8);

    private static String sign(String key) {
        return HexFormat.of().formatHex(Webhooks.hmac(key, BODY));
    }

    /** Binds WEBHOOK_KEYS as Spring does at startup, after docuconf's check passes. */
    private static List<String> keys(String value) {
        var result = DocuconfTester.env(Map.of("ORDERS_DATABASEURL", DB, "WEBHOOK_KEYS", value)).check();
        assertEquals(List.of(), result.codes());
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", Map.of("WEBHOOK_KEYS", value)));
        return Binder.get(env).bind("webhook", WebhookProperties.class).get().keys();
    }

    /** A key rotation: each step is a rollout with a new WEBHOOK_KEYS, and the key in use always verifies. */
    @Test
    void aRotationNeverTurnsAwayAWebhook() {
        record Step(String name, String keys, boolean old, boolean neu) {
        }
        for (Step s : List.of(new Step("before", OLD, true, false), new Step("overlap", OLD + "," + NEW, true, true),
                new Step("after", NEW, false, true))) {
            List<String> set = keys(s.keys());
            assertEquals(s.old(), Webhooks.verify(set, BODY, sign(OLD)), s.name() + ": old key");
            assertEquals(s.neu(), Webhooks.verify(set, BODY, sign(NEW)), s.name() + ": new key");
            assertFalse(Webhooks.verify(set, BODY, sign("x".repeat(32))), s.name() + ": another key");
        }
    }

    @Test
    void aBadOrMissingSignatureIsRejected() {
        assertFalse(Webhooks.verify(List.of(OLD), BODY, "not hex"));
        assertFalse(Webhooks.verify(List.of(OLD), BODY, null));
        assertFalse(Webhooks.verify(null, BODY, sign(OLD))); // no keys configured
    }

    /** An empty or truncated key, or a third key, fails the startup check without printing any key. */
    @Test
    void aBadKeySetFailsAtStartup() {
        Map<String, String> cases = Map.of(
                OLD + ",", "out_of_range WEBHOOK_KEYS",
                OLD + "," + NEW.substring(0, 10), "out_of_range WEBHOOK_KEYS",
                OLD + "," + NEW + "," + "x".repeat(32), "too_many_items WEBHOOK_KEYS");
        cases.forEach((value, want) -> {
            var result = DocuconfTester.env(Map.of("ORDERS_DATABASEURL", DB, "WEBHOOK_KEYS", value)).check();
            assertEquals(List.of(want), result.codes(), value.length() + " characters");
            String printed = result.violations().toString();
            assertFalse(printed.contains(OLD) || printed.contains(NEW.substring(0, 10)), printed);
        });
    }
}
