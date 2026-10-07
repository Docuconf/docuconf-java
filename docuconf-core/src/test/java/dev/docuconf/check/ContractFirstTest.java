package dev.docuconf.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.contract.Json;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractFirstTest {

    @TempDir
    Path tmp;

    private static final String CONTRACT = """
            {
              "apiVersion": "docuconf.dev/v1alpha1",
              "kind": "ConfigContract",
              "metadata": {"name": "orders"},
              "vars": {
                "PORT": {"type": "int", "description": "Port to listen on", "min": 1, "max": 65535, "default": 8080},
                "TIMEOUT": {"type": "duration", "description": "Request timeout", "encoding": "timespan"},
                "SHARDS": {"type": "list", "description": "Shard ids", "items": "int", "encoding": "indexed",
                           "itemMin": 0, "itemMax": 1023},
                "TOKEN": {"type": "string", "description": "API token", "secret": true, "required": true,
                          "minLength": 20}
              },
              "files": {
                "rules": {"type": "config", "format": "json", "description": "Routing rules",
                          "path": "/etc/orders/rules/rules.json", "required": true,
                          "schema": {"type": "object", "required": ["routes"],
                                     "properties": {"routes": {"type": "array", "items": {"type": "string"}}}}}
              }
            }
            """;

    @Test
    void loadsTypedValuesAndFiles() throws Exception {
        Files.createDirectories(tmp.resolve("etc/orders/rules"));
        Files.writeString(tmp.resolve("etc/orders/rules/rules.json"), "{\"routes\":[\"a\"]}");
        ContractFirst.Result r = ContractFirst.load(CONTRACT, Map.of(
                "TIMEOUT", "00:01:30.25",
                "SHARDS__0", "3", "SHARDS__1", "1023",
                "TOKEN", "tok_0123456789abcdefghij",
                "DOCUCONF_FILE_ROOT", tmp.toString()));
        assertTrue(r.ok(), r.violations().toString());
        Map<String, Object> v = r.require();
        assertEquals(8080L, v.get("PORT"));
        assertEquals(Duration.ofMillis(90_250), v.get("TIMEOUT"));
        assertEquals(List.of(3L, 1023L), v.get("SHARDS"));
        assertEquals(Map.of("routes", List.of("a")), r.files().get("rules"));
    }

    @Test
    void reportsEveryProblemWithoutSecrets() throws Exception {
        Files.createDirectories(tmp.resolve("etc/orders/rules"));
        Files.writeString(tmp.resolve("etc/orders/rules/rules.json"), "{\"routes\":[1]}");
        ContractFirst.Result r = ContractFirst.load(CONTRACT, Map.of(
                "PORT", "99999999999999999999",
                "TIMEOUT", "24:00:00",
                "SHARDS__0", "1024",
                "TOKEN", "short-secret-value",
                "DOCUCONF_FILE_ROOT", tmp.toString()));
        List<String> codes = r.violations().stream().map(x -> x.code().id() + " " + x.input()).sorted().toList();
        assertEquals(List.of("invalid_type TIMEOUT", "out_of_range PORT", "out_of_range SHARDS",
                "out_of_range TOKEN", "schema_mismatch rules"), codes);
        ContractFirst.InvalidConfigurationException e = assertThrows(
                ContractFirst.InvalidConfigurationException.class, r::require);
        assertFalse(e.getMessage().contains("short-secret-value"), e.getMessage());
        assertTrue(e.getMessage().contains("5 problems"), e.getMessage());
    }

    @Test
    void indexedItemsAreNumberedFromZeroWithNoGap() throws Exception {
        Files.createDirectories(tmp.resolve("etc/orders/rules"));
        Files.writeString(tmp.resolve("etc/orders/rules/rules.json"), "{\"routes\":[]}");
        Map<String, String> base = Map.of("TOKEN", "tok_0123456789abcdefghij", "DOCUCONF_FILE_ROOT", tmp.toString());
        for (Map<String, String> shards : List.of(Map.of("SHARDS__0", "1", "SHARDS__2", "3"),
                Map.of("SHARDS__1", "2"))) {
            Map<String, String> env = new java.util.HashMap<>(base);
            env.putAll(shards);
            List<Violation> found = ContractFirst.load(CONTRACT, env).violations();
            assertEquals(1, found.size(), found.toString());
            assertEquals(Code.INVALID_TYPE, found.get(0).code());
            assertTrue(found.get(0).message().contains("SHARDS__0") || found.get(0).message().contains("SHARDS__1"),
                    found.get(0).message());
        }
        // Only decimal indices with no leading zero are items: SHARDS__01 and SHARDS__HOST are other variables.
        Map<String, String> env = new java.util.HashMap<>(base);
        env.putAll(Map.of("SHARDS__0", "7", "SHARDS__01", "8", "SHARDS__HOST", "x"));
        ContractFirst.Result r = ContractFirst.load(CONTRACT, env);
        assertTrue(r.ok(), r.violations().toString());
        assertEquals(List.of(7L), r.require().get("SHARDS"));
    }

    @Test
    void rejectsAnInvalidContract() {
        String bad = CONTRACT.replace("\"default\": 8080", "\"default\": 70000");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContractFirst.load(bad, Map.of()));
        assertTrue(e.getMessage().contains("PORT: default"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ContractFirst.load("{\"kind\":\"Other\"}", Map.of()));
    }

    @Test
    void theSelectedProfileSuppliesDefaults() {
        String contract = """
                {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "svc"},
                 "vars": {
                   "APP_ENV": {"type": "string", "description": "Profile to run"},
                   "API_URL": {"type": "url", "description": "Upstream API", "required": true},
                   "WORKERS": {"type": "int", "description": "Worker threads", "default": 2}},
                 "profiles": {"selector": "APP_ENV", "default": "prod",
                   "defaults": {"prod": {"API_URL": "https://api", "WORKERS": 8}, "dev": {"API_URL": "http://x"}}}}
                """;
        Map<String, Object> prod = ContractFirst.load(contract, Map.of()).require();
        assertEquals("https://api", prod.get("API_URL"));
        assertEquals(8L, prod.get("WORKERS"));
        Map<String, Object> dev = ContractFirst.load(contract, Map.of("APP_ENV", "dev", "WORKERS", "3")).require();
        assertEquals("http://x", dev.get("API_URL"));
        assertEquals(3L, dev.get("WORKERS"));
        assertEquals(List.of("missing_required"), ContractFirst.load(contract, Map.of("APP_ENV", "qa"))
                .violations().stream().map(v -> v.code().id()).toList());
    }

    @Test
    void goDurations() {
        assertEquals(Duration.ofMinutes(90), WireFormat.parseDuration("1.5h", "go"));
        assertEquals(Duration.ofNanos(1500), WireFormat.parseDuration("1.5us", "go"));
        assertEquals(Duration.ofNanos(1500), WireFormat.parseDuration("1.5µs", "go"));
        assertEquals(Duration.ZERO, WireFormat.parseDuration("0", "go"));
        assertEquals(Duration.ofSeconds(-1), WireFormat.parseDuration("-1s", null));
        assertEquals(Duration.ofMillis(500), WireFormat.parseDuration(".5s", "go"));
        for (String bad : new String[] {"", "1", "1h1", "PT90S", "1 h", "s", ".s", "1d"}) {
            assertThrows(WireFormat.WireException.class, () -> WireFormat.parseDuration(bad, "go"), bad);
        }
        assertThrows(WireFormat.WireException.class, () -> WireFormat.parseDuration("3000000h", "go"));
    }

    @Test
    void otherDurationEncodings() {
        assertEquals(Duration.ofHours(26), WireFormat.parseDuration("P1DT2H", "iso8601"));
        assertEquals(Duration.ofMillis(1500), WireFormat.parseDuration("PT1,5S", "iso8601"));
        assertEquals(Duration.ofDays(2), WireFormat.parseDuration("P2D", "iso8601"));
        for (String bad : new String[] {"P", "PT", "P1DT", "PT-1S", "P1Y", "P1W", "pt1s", "1s"}) {
            assertThrows(WireFormat.WireException.class, () -> WireFormat.parseDuration(bad, "iso8601"), bad);
        }
        assertEquals(Duration.ofMillis(1500), WireFormat.parseDuration("1.5", "seconds"));
        for (String bad : new String[] {"-1", "1e3", ".5", "1.", "1s"}) {
            assertThrows(WireFormat.WireException.class, () -> WireFormat.parseDuration(bad, "seconds"), bad);
        }
        assertEquals(Duration.ofNanos(100), WireFormat.parseDuration("00:00:00.0000001", "timespan"));
        for (String bad : new String[] {"24:00:00", "00:60:00", "1:2:3", "00:00:00.12345678", "1d.00:00:00"}) {
            assertThrows(WireFormat.WireException.class, () -> WireFormat.parseDuration(bad, "timespan"), bad);
        }
    }

    @Test
    void numbers() {
        assertEquals(Long.MAX_VALUE, WireFormat.parseInt("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, WireFormat.parseInt("-9223372036854775808"));
        assertEquals(5L, WireFormat.parseInt("+5"));
        assertEquals(Code.OUT_OF_RANGE, assertThrows(WireFormat.WireException.class,
                () -> WireFormat.parseInt("9223372036854775808")).code());
        assertEquals(Code.INVALID_TYPE, assertThrows(WireFormat.WireException.class,
                () -> WireFormat.parseInt("1e3")).code());
        assertEquals(0, new BigDecimal("1000").compareTo(WireFormat.parseFloat("1e3")));
        for (String bad : new String[] {"NaN", "Inf", "-Infinity", "0,5", "1e400", "", "0x10"}) {
            assertThrows(WireFormat.WireException.class, () -> WireFormat.parseFloat(bad), bad);
        }
        assertEquals(List.of(1L, 2L), WireFormat.parseJsonList("[1, 2]", "int"));
        assertEquals(Code.OUT_OF_RANGE, assertThrows(WireFormat.WireException.class,
                () -> WireFormat.parseJsonList("[99999999999999999999]", "int")).code());
        assertEquals(Code.INVALID_TYPE, assertThrows(WireFormat.WireException.class,
                () -> WireFormat.parseJsonList("[1.0]", "int")).code());
        assertEquals(List.of("a", "", "b"), WireFormat.splitCsv("a||b", "|"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonSchema() {
        Map<String, Object> schema = (Map<String, Object>) Json.parse("""
                {"$defs": {"port": {"type": "integer", "minimum": 1, "maximum": 65535}},
                 "type": "object", "additionalProperties": false, "required": ["port"],
                 "properties": {
                   "port": {"$ref": "#/$defs/port"},
                   "name": {"type": "string", "pattern": "^[a-z]+$", "maxLength": 5},
                   "tags": {"type": "array", "uniqueItems": true, "items": {"enum": ["a", "b"]}},
                   "ratio": {"type": "number", "exclusiveMaximum": 1},
                   "mode": {"oneOf": [{"const": "x"}, {"type": "integer"}]}}}
                """);
        assertEquals(List.of(), JsonSchema.validate(schema,
                Json.parse("{\"port\": 8080.0, \"name\": \"abc\", \"tags\": [\"a\"], \"ratio\": 0.5, \"mode\": 3}"),
                false));
        List<String> problems = JsonSchema.validate(schema, Json.parse(
                "{\"port\": 0, \"name\": \"ABCDEFG\", \"tags\": [\"a\", \"a\", \"c\"], \"ratio\": 1, \"mode\": \"y\","
                        + " \"extra\": true}"), true);
        String all = String.join("\n", problems);
        assertTrue(all.contains("$.port: is below minimum 1"), all);
        assertTrue(all.contains("$.name: is longer than 5"), all);
        assertTrue(all.contains("$.name: does not match"), all);
        assertTrue(all.contains("$.tags: has duplicate items"), all);
        assertTrue(all.contains("$.tags[2]: must be one of"), all);
        assertTrue(all.contains("$.ratio: must be below 1"), all);
        assertTrue(all.contains("$.mode: matches 0 of the oneOf"), all);
        assertTrue(all.contains("$: property extra is not allowed"), all);
        assertFalse(all.contains("ABCDEFG"), all);
    }
}
