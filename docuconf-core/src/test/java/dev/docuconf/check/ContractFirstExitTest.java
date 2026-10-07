package dev.docuconf.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one-liner boot: a clean report and exit status 1, typo hints, and duration syntax help. */
class ContractFirstExitTest {

    @TempDir
    Path tmp;

    private static final String CONTRACT = """
            {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "orders"},
             "vars": {
               "ORDERS_PORT": {"type": "int", "description": "Port to listen on", "min": 1, "max": 65535},
               "ORDERS_TIMEOUT": {"type": "duration", "description": "Request timeout", "encoding": "iso8601"},
               "ORDERS_TOKEN": {"type": "string", "description": "API token", "secret": true, "required": true}
             }}
            """;

    private record Run(int status, String out, String err) {
    }

    private Run run(Map<String, String> env) throws Exception {
        Path contract = tmp.resolve("contract.json");
        Files.writeString(contract, CONTRACT);
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), BootOrExitMain.class.getName(), contract.toString());
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.environment().put("DOCUCONF_TERMINATION_LOG", tmp.resolve("termination-log").toString());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return new Run(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void failurePrintsTheProblemsAndExitsOne() throws Exception {
        Run r = run(Map.of("ORDERS_PORT", "0", "ORDERS_TIMEOUT", "30s", "ORDERS_PROT", "x"));
        assertEquals(1, r.status(), r.err());
        List<String> lines = r.err().lines().filter(l -> !l.startsWith("Picked up")).toList();
        assertEquals(List.of(
                "docuconf: ORDERS_PROT is set but not declared; did you mean ORDERS_PORT?",
                "docuconf: 3 configuration problems:",
                "  [out_of_range] ORDERS_PORT: is below min 1 (got 0)",
                "  [invalid_type] ORDERS_TIMEOUT: is not a duration; expected an ISO 8601 duration like PT30S"
                        + " (got \"30s\")",
                "  [missing_required] ORDERS_TOKEN: is required but not set"), lines);
        assertFalse(r.err().contains("Exception"), r.err());
        assertTrue(Files.readString(tmp.resolve("termination-log")).contains("[out_of_range] ORDERS_PORT"));
    }

    @Test
    void successReturnsTheValues() throws Exception {
        Run r = run(Map.of("ORDERS_PORT", "8080", "ORDERS_TOKEN", "secret-token"));
        assertEquals(0, r.status(), r.err());
        assertEquals("ORDERS_PORT=8080", r.out().strip());
    }
}
