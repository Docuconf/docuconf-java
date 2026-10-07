package dev.docuconf.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.docuconf.Redacted;
import dev.docuconf.Secret;
import org.junit.jupiter.api.Test;

class RedactedTest {

    record Db(@Secret String password, String host, int port) {
        @Override
        public String toString() {
            return Redacted.toString(this);
        }
    }

    static class Bean {
        @Secret
        private String token = "hunter2";
        private String region = "eu1";
    }

    @Test
    void recordsPrintLikeRecordsWithSecretsRedacted() {
        assertEquals("Db[password=[redacted], host=db.internal, port=5432]",
                new Db("hunter2", "db.internal", 5432).toString());
    }

    @Test
    void classesPrintTheirFieldsWithSecretsRedacted() {
        assertEquals("Bean(token=[redacted], region=eu1)", Redacted.toString(new Bean()));
    }
}
