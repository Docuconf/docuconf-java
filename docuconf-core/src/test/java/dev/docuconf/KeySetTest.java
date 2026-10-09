package dev.docuconf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeySetTest {

    @Test
    void keysInOrderAndNeverPrinted() {
        KeySet keys = KeySet.of("old-secret-key", "new-secret-key");
        assertEquals(List.of("old-secret-key", "new-secret-key"), keys.keys());
        assertEquals(2, keys.size());
        assertEquals("KeySet[2 keys, [redacted]]", keys.toString());
        assertThrows(UnsupportedOperationException.class, () -> keys.keys().add("x"));
    }

    @Test
    void containsComparesEveryKey() {
        KeySet keys = KeySet.of("old-secret-key", "new-secret-key");
        assertTrue(keys.contains("old-secret-key"));
        assertTrue(keys.contains("new-secret-key"));
        assertFalse(keys.contains("new-secret-ke"));
        assertFalse(keys.contains(" new-secret-key"));
        assertFalse(keys.contains(null));
    }

    @Test
    void anInboundApiKey() {
        KeySet apiKeys = KeySet.of("old-api-key-0123", "new-api-key-0123");
        String header = "new-api-key-0123";
        boolean known = apiKeys.contains(header);
        assertTrue(known);
    }

    @Test
    void anyMatchRunsTheCheckOnEveryKey() {
        KeySet keys = KeySet.of("a", "b", "c");
        List<String> seen = new ArrayList<>();
        assertTrue(keys.anyMatch(k -> {
            seen.add(k);
            return k.equals("a");
        }));
        assertEquals(List.of("a", "b", "c"), seen, "no early exit at the first match");
        assertFalse(keys.anyMatch(k -> false));
    }

    record Holder(KeySet keys, int port) {
    }

    @Test
    void redactedPrintsAKeySetAsRedacted() {
        assertEquals("Holder[keys=[redacted], port=80]", Redacted.toString(new Holder(KeySet.of("k1"), 80)));
    }
}
