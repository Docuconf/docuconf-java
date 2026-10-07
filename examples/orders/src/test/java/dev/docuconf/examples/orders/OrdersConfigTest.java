package dev.docuconf.examples.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.spring.DocuconfTester;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// docuconf: start test
class OrdersConfigTest {

    private static final String DB = "postgres://orders:secret@db.internal:5432/orders";

    @Test
    void aValidEnvironmentPasses() {
        var result = DocuconfTester.env(Map.of("ORDERS_DATABASEURL", DB, "ORDERS_LOGLEVEL", "warn")).check();
        assertTrue(result.ok(), result.violations().toString());
    }

    @Test
    void everyProblemIsReported() {
        var result = DocuconfTester.env(Map.of("ORDERS_PORT", "0", "ORDERS_REQUESTTIMEOUT", "1m30s")).check();
        assertEquals(List.of(
                "invalid_type ORDERS_REQUESTTIMEOUT",
                "missing_required ORDERS_DATABASEURL",
                "out_of_range ORDERS_PORT"), result.codes());
    }

    @Test
    void aTypoGetsAHint() {
        var result = DocuconfTester.env(Map.of("ORDERS_DATABASEURL", DB, "ORDERS_PROT", "9090")).check();
        assertEquals(List.of("ORDERS_PROT is set but not declared; did you mean ORDERS_PORT?"), result.warnings());
    }
}
// docuconf: end test
