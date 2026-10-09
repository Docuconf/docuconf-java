package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hints and messages at startup, through {@link DocuconfTester} (which never reads the process environment). */
class RuntimeHintsTest {

    @TempDir
    Path tmp;

    Map<String, String> env;

    @BeforeEach
    void setUp() {
        ShopFixture shop = new ShopFixture(tmp);
        env = new HashMap<>();
        shop.env.forEach((k, v) -> env.put(k, v.toString()));
        env.remove("DOCUCONF_FILE_ROOT");
    }

    private DocuconfTester.Result check() {
        return DocuconfTester.env(env).fileRoot(tmp).check();
    }

    @Test
    void aValidEnvironmentPassesWithoutTouchingTheProcess() {
        DocuconfTester.Result r = check();
        assertTrue(r.ok(), r.violations().toString());
        assertNull(System.getenv("SHOP_DATABASEURL"));
    }

    @Test
    void aTypoedVariableGetsAHintWithoutItsValue() {
        env.put("SHOP_PROT", "9090-hidden");
        env.put("SHOP_KEYSTORE_PASSWORD", ShopFixture.KS_PASSWORD); // Spring binds this form: no hint
        env.put("UNRELATED_PROT", "x");
        DocuconfTester.Result r = check();
        assertTrue(r.ok(), r.violations().toString());
        assertEquals(List.of("SHOP_PROT is set but not declared; did you mean SHOP_PORT?"),
                r.warnings().stream().filter(w -> w.contains("not declared")).toList());
        assertFalse(r.warnings().toString().contains("9090-hidden"));
    }

    @Test
    void violationsNameTheVariableThatWasActuallySet() {
        env.remove("SHOP_DATABASEURL");
        env.put("SHOP_DATABASE_URL", "mysql://shop:" + ShopFixture.DB_SECRET + "@db/shop");
        DocuconfTester.Result r = check();
        assertEquals(List.of("invalid_scheme SHOP_DATABASEURL"), r.codes());
        String message = r.violations().get(0).message();
        assertTrue(message.endsWith("(set as SHOP_DATABASE_URL)"), message);
        assertFalse(message.contains(ShopFixture.DB_SECRET), message);
    }

    @Test
    void aKeySetIsCheckedWithoutPrintingAKey() {
        String key = "key-0123456789";
        env.put("SHOP_WEBHOOKKEYS", key + ",");
        DocuconfTester.Result r = check();
        assertEquals(List.of("out_of_range SHOP_WEBHOOKKEYS"), r.codes());
        assertEquals("key 1 is empty (a stray separator?)", r.violations().get(0).message());

        env.put("SHOP_WEBHOOKKEYS", key + "," + key + "-new," + key + "-third");
        r = check();
        assertEquals(List.of("too_many_items SHOP_WEBHOOKKEYS"), r.codes());

        env.put("SHOP_WEBHOOKKEYS", "short," + key);
        r = check();
        assertEquals(List.of("out_of_range SHOP_WEBHOOKKEYS"), r.codes());
        assertEquals("key 0 is 5 characters, below keyMinLength 8", r.violations().get(0).message());
        assertFalse(r.toString().contains(key), r.toString());

        env.put("SHOP_WEBHOOKKEYS", " " + key + "," + key);
        assertTrue(check().ok(), "keys are never trimmed, so a padded key is just a longer key");
    }

    @Test
    void aDeprecatedVariableThatIsSetLoadsWithAWarning() {
        env.put("SHOP_LEGACYPORT", "7070");
        DocuconfTester.Result r = check();
        assertTrue(r.ok(), r.violations().toString());
        assertEquals(List.of("SHOP_LEGACYPORT is deprecated: Use SHOP_PORT instead"),
                r.warnings().stream().filter(w -> w.contains("deprecated")).toList());
        assertFalse(r.warnings().toString().contains("7070"));
    }

    @Test
    void valuesSpringWouldReadMoreLenientlyAreRejected() {
        for (String[] c : new String[][] {{"SHOP_PORT", "0x10"}, {"SHOP_PORT", "#10"}, {"SHOP_PORT", "1e3"},
                {"SHOP_TIMEOUT", "30s"}, {"SHOP_TIMEOUT", "pt30s"},
                {"SHOP_ORIGINS", "https://a.example, https://b.example"},
                {"SHOP_ORIGINS", "https://a.example,,https://b.example"}, {"SHOP_SHARDS", "1,0x2"}}) {
            Map<String, String> before = new HashMap<>(env);
            env.put(c[0], c[1]);
            DocuconfTester.Result r = check();
            assertEquals(List.of("invalid_type " + c[0]), r.codes(), c[0] + "=" + c[1] + ": " + r);
            env.clear();
            env.putAll(before);
        }
    }

    @Test
    void paddedNumbersAreRejectedAsTheContractRejectsThem() {
        env.put("SHOP_PORT", " 8080");
        DocuconfTester.Result r = check();
        assertEquals(List.of("invalid_type SHOP_PORT"), r.codes());
        assertTrue(r.violations().get(0).message().contains("leading or trailing whitespace"), r.toString());
    }

    @Test
    void secretBeanValidationMessagesAreReadableAndRedacted() {
        env.put("SHOP_ALERTEMAIL", "hunter2-not-an-email");
        DocuconfTester.Result r = check();
        assertEquals(List.of("invalid_type SHOP_ALERTEMAIL"), r.codes());
        assertEquals("must be a well-formed email address (@Email)", r.violations().get(0).message());
        assertFalse(r.toString().contains("hunter2"));
    }

    @Test
    void enumsExportedInLowerCaseAcceptEitherCase() {
        env.put("SHOP_MODE", "safe");
        assertTrue(check().ok());
        env.put("SHOP_MODE", "SAFE");
        assertTrue(check().ok());
        env.put("SHOP_MODE", "slow");
        DocuconfTester.Result r = check();
        assertEquals(List.of("not_in_enum SHOP_MODE"), r.codes());
        assertTrue(r.violations().get(0).message().contains("use one of fast, safe"), r.toString());
    }

    @Test
    void goStyleDurationsExplainTheExpectedForm() {
        env.put("SHOP_TIMEOUT", "1m30s");
        DocuconfTester.Result r = check();
        assertEquals(List.of("invalid_type SHOP_TIMEOUT"), r.codes());
        assertTrue(r.violations().get(0).message().contains("expected an ISO 8601 duration like PT30S"),
                r.toString());
    }

    @Test
    void fileInputNamesAreChecked() {
        DocuconfFiles files = new DocuconfFiles();
        files.put("rates", tmp.resolve("rates.yaml"), "v1", "watch");
        files.put("tls", tmp.resolve("tls"), null, "restart");
        List<String> seen = new java.util.ArrayList<>();
        files.onChange("rates", String.class, seen::add);
        files.update("rates", "v2");
        assertEquals(List.of("v2"), seen);
        IllegalArgumentException typo = assertThrows(IllegalArgumentException.class,
                () -> files.onChange("ratse", String.class, seen::add));
        assertEquals("docuconf: no file input 'ratse'; inputs: rates, tls", typo.getMessage());
        IllegalArgumentException restart = assertThrows(IllegalArgumentException.class,
                () -> files.onChange("tls", Object.class, v -> { }));
        assertTrue(restart.getMessage().contains("declare it with reload = Reload.WATCH"), restart.getMessage());
        assertThrows(IllegalArgumentException.class, () -> files.get("ratse", String.class));
        assertThrows(IllegalArgumentException.class, () -> files.onChange("rates", Integer.class, v -> { }));
    }
}
