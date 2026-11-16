package dev.docuconf.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.Json;
import dev.docuconf.contract.RequiredIf;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Conditional requirements (SPEC §4.2.1), roles (SPEC §4.9) and fragments (SPEC §4.8) in contract-first mode. */
class ConditionalTest {

    @TempDir
    Path tmp;

    private static final String RELAY = """
            {
              "apiVersion": "docuconf.dev/v1alpha1",
              "kind": "ConfigContract",
              "metadata": {"name": "relay"},
              "includes": ["otel@1"],
              "vars": {
                "TLS_ENABLED": {"type": "bool", "description": "Serve HTTPS", "default": false},
                "LOG_FORMAT": {"type": "enum", "description": "Log output format", "values": ["text", "json"],
                               "default": "text"},
                "LOG_SCHEMA_URL": {"type": "url", "description": "Schema of the logs",
                                   "requiredIf": {"LOG_FORMAT": "json"}},
                "CA_SUBJECT": {"type": "string", "description": "Client CA subject",
                               "requiredIf": {"TLS_ENABLED": true}},
                "OTEL_SERVICE_NAME": {"type": "string", "description": "Service name reported in telemetry"}
              },
              "files": {
                "allowlist": {"type": "text", "description": "Allowed clients", "path": "/etc/relay/allow/allow.txt",
                              "requiredIf": {"TLS_ENABLED": true}}
              }
            }
            """;

    private static final String SHOP = """
            {
              "apiVersion": "docuconf.dev/v1alpha1",
              "kind": "ConfigContract",
              "metadata": {"name": "shop"},
              "roles": ["web", "worker"],
              "vars": {
                "DOCUCONF_ROLE": {"type": "enum", "description": "The role this workload runs", "required": true,
                                  "values": ["web", "worker"]},
                "ORIGINS": {"type": "list", "items": "string", "description": "Allowed origins", "required": true,
                            "roles": ["web"]},
                "QUEUES": {"type": "list", "items": "string", "description": "Job queues", "required": true,
                           "roles": ["worker"]},
                "BATCH": {"type": "int", "description": "Jobs per batch", "roles": ["worker"],
                          "requiredIf": {"DOCUCONF_ROLE": "worker"}}
              },
              "files": {
                "robots": {"type": "text", "description": "The robots.txt served", "required": true,
                           "roles": ["web"], "path": "/etc/shop/robots/robots.txt"}
              }
            }
            """;

    private List<String> problems(String contract, Map<String, String> env) {
        Map<String, String> e = new HashMap<>(env);
        e.put(FileChecker.FILE_ROOT_ENV, tmp.toString());
        return ContractFirst.load(contract, e).violations().stream()
                .map(v -> v.toString().replace(tmp.toString(), "")).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private static Contract read(String json) {
        return ContractJson.fromMap((Map<String, Object>) Json.parse(json));
    }

    @Test
    void readsTheConditionsRolesAndIncludes() {
        Contract relay = read(RELAY);
        assertEquals(List.of("otel@1"), relay.includes);
        assertEquals(new RequiredIf("TLS_ENABLED", true), relay.vars.get("CA_SUBJECT").requiredIf);
        assertEquals("LOG_FORMAT is \"json\"", relay.vars.get("LOG_SCHEMA_URL").requiredIf.toString());
        assertEquals("TLS_ENABLED", relay.files.get("allowlist").requiredIf.var());

        Contract shop = read(SHOP);
        assertEquals(List.of("web", "worker"), shop.roles);
        assertEquals(List.of("web"), shop.vars.get("ORIGINS").roles);
        assertEquals(List.of("web"), shop.files.get("robots").roles);

        // They round-trip through the JSON the SDK writes.
        Contract again = ContractJson.fromMap(ContractJson.toMap(shop));
        assertEquals(shop.roles, again.roles);
        assertEquals(shop.vars.get("BATCH").requiredIf, again.vars.get("BATCH").requiredIf);
        assertEquals(relay.includes, ContractJson.fromMap(ContractJson.toMap(relay)).includes);
    }

    @Test
    void aConditionThatHoldsMakesItsInputsRequired() {
        assertEquals(List.of(), problems(RELAY, Map.of()));
        assertEquals(List.of(
                "[file_missing] allowlist: /etc/relay/allow/allow.txt does not exist, and is required when"
                        + " TLS_ENABLED is true",
                "[missing_required] CA_SUBJECT: is required when TLS_ENABLED is true",
                "[missing_required] LOG_SCHEMA_URL: is required when LOG_FORMAT is \"json\""),
                problems(RELAY, Map.of("TLS_ENABLED", "true", "LOG_FORMAT", "json")));
        assertEquals(List.of(), problems(RELAY, Map.of("LOG_FORMAT", "json",
                "LOG_SCHEMA_URL", "https://logs.example.com/v1.json")));
    }

    @Test
    void aConditionOnAnIntIsWrittenAsANumber() {
        String contract = """
                {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "relay"},
                 "vars": {
                   "PORT": {"type": "int", "description": "Port to listen on", "default": 443},
                   "ACME_EMAIL": {"type": "string", "description": "Renewal contact", "requiredIf": {"PORT": 443}}}}
                """;
        assertEquals(List.of("[missing_required] ACME_EMAIL: is required when PORT is 443"),
                problems(contract, Map.of()));
        assertEquals(List.of(), problems(contract, Map.of("PORT", "8080")));
    }

    @Test
    void anInvalidConditionVariableMakesNothingRequired() {
        List<String> got = problems(RELAY, Map.of("TLS_ENABLED", "yes"));
        assertEquals(1, got.size(), got.toString());
        assertTrue(got.get(0).startsWith("[invalid_type] TLS_ENABLED:"), got.toString());
    }

    @Test
    void aRoleRequiresOnlyItsOwnInputs() {
        assertEquals(List.of("[file_missing] robots: /etc/shop/robots/robots.txt does not exist",
                "[missing_required] ORIGINS: is required but not set"),
                problems(SHOP, Map.of("DOCUCONF_ROLE", "web")));
        assertEquals(List.of("[missing_required] BATCH: is required when DOCUCONF_ROLE is \"worker\"",
                "[missing_required] QUEUES: is required but not set"),
                problems(SHOP, Map.of("DOCUCONF_ROLE", "worker")));
        assertEquals(List.of(), problems(SHOP, Map.of("DOCUCONF_ROLE", "worker", "QUEUES", "mail", "BATCH", "10")));

        // With no role, or an unknown one, the role is the one problem reported.
        assertEquals(List.of("[missing_required] DOCUCONF_ROLE: is required but not set"), problems(SHOP, Map.of()));
        List<String> unknown = problems(SHOP, Map.of("DOCUCONF_ROLE", "cron"));
        assertEquals(1, unknown.size(), unknown.toString());
        assertTrue(unknown.get(0).startsWith("[not_in_enum] DOCUCONF_ROLE:"), unknown.toString());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"A": {"type": "string", "description": "An input", "requiredIf": {"A": "x"}}} | A: requiredIf must name another declared variable, not A
            {"A": {"type": "string", "description": "An input", "requiredIf": {"B": "x"}}} | A: requiredIf must name another declared variable, not B
            {"A": {"type": "string", "description": "An input", "required": true, "requiredIf": {"B": true}}, "B": {"type": "bool", "description": "A flag"}} | A: an input with requiredIf is neither required nor has a default
            {"A": {"type": "string", "description": "An input", "default": "a", "requiredIf": {"B": true}}, "B": {"type": "bool", "description": "A flag"}} | A: an input with requiredIf is neither required nor has a default
            {"A": {"type": "string", "description": "An input", "deprecated": {"message": "Going away"}, "requiredIf": {"B": true}}, "B": {"type": "bool", "description": "A flag"}} | A: an input with requiredIf cannot be deprecated, since the platform could not stop setting it; remove requiredIf first
            {"A": {"type": "string", "description": "An input", "requiredIf": {"B": 1}}, "B": {"type": "bool", "description": "A flag"}} | A: requiredIf: B is 1 is not a value B can have
            {"A": {"type": "string", "description": "An input", "requiredIf": {"B": "gold"}}, "B": {"type": "enum", "description": "A tier", "values": ["free", "paid"]}} | A: requiredIf: B is "gold" is not a value B can have: must be one of free, paid
            {"A": {"type": "string", "description": "An input", "requiredIf": {"B": "x"}}, "B": {"type": "string", "description": "A secret", "secret": true}} | A: requiredIf names B, which is not a plain string, int, bool or enum variable
            {"A": {"type": "string", "description": "An input", "requiredIf": {"B": "1s"}}, "B": {"type": "duration", "description": "A timeout"}} | A: requiredIf names B, which is not a plain string, int, bool or enum variable
            {"A": {"type": "string", "description": "An input", "requiredIf": {"B": "x", "C": "y"}}} | A: requiredIf must name one variable and its value: {VAR: value}
            {"A": {"type": "string", "description": "An input", "requiredIf": {"B": 1.5}}} | A: requiredIf must name one variable and a string, integer or boolean value
            {"A": {"type": "string", "description": "An input", "roles": ["web"]}} | A: has roles, but the contract declares none
            {"A": {"type": "string", "description": "An input", "roles": []}} | A: roles must be a list of at least one role name
            """)
    void rejectsABadConditionOrRole(String vars, String error) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ContractFirst.load(
                "{\"apiVersion\": \"docuconf.dev/v1alpha1\", \"kind\": \"ConfigContract\","
                        + " \"metadata\": {\"name\": \"app\"}, \"vars\": " + vars + "}", Map.of()));
        assertTrue(e.getMessage().contains(error), e.getMessage());
    }

    @Test
    void rolesNeedTheRoleVariable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ContractFirst.load("""
                {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "app"},
                 "roles": ["web", "web", "Web"], "includes": ["otel@1", "otel@1", "otel"],
                 "vars": {"A": {"type": "string", "description": "An input", "roles": ["cron"]}}}
                """, Map.of()));
        String m = e.getMessage();
        assertTrue(m.contains("roles: role web is listed twice"), m);
        assertTrue(m.contains("roles: role \"Web\" must be a DNS label"), m);
        assertTrue(m.contains("includes: otel@1 is listed twice"), m);
        assertTrue(m.contains("includes: \"otel\" is not a fragment name, name@major"), m);
        assertTrue(m.contains("A: role cron is not one of the contract's roles, web, web, Web"), m);
        assertTrue(m.contains("DOCUCONF_ROLE: a contract with roles declares it as a required enum"), m);

        // DOCUCONF_ROLE is used by every role, and its values are exactly the roles.
        e = assertThrows(IllegalArgumentException.class, () -> ContractFirst.load("""
                {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "app"},
                 "roles": ["web", "worker"],
                 "vars": {"DOCUCONF_ROLE": {"type": "enum", "description": "The role this workload runs",
                                            "required": true, "values": ["web"], "roles": ["web"]}}}
                """, Map.of()));
        assertTrue(e.getMessage().contains("DOCUCONF_ROLE: a contract with roles declares it"), e.getMessage());
    }
}
