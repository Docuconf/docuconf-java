package dev.docuconf.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.docuconf.testing.CueVet;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contract the annotation processor exported for this app, at build time, matches the golden file (which
 * uses every variable type and every file input type) and passes {@code cue vet} against the meta-schema.
 *
 * <p>Regenerate the golden file with {@code mvn test -Ddocuconf.updateGolden=true}.
 */
class GoldenContractTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/contract.cue");

    @TempDir
    Path tmp;

    static String exported() throws Exception {
        try (InputStream in = GoldenContractTest.class.getResourceAsStream("/META-INF/docuconf/contract.cue")) {
            assertNotNull(in, "the processor did not write META-INF/docuconf/contract.cue");
            // metadata.generator is excluded from the conformance comparison (SPEC §11.2 item 3).
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("version: \"[^\"]*\"}", "version: \"VERSION\"}");
        }
    }

    @Test
    void matchesGolden() throws Exception {
        String exported = exported();
        if (Boolean.getBoolean("docuconf.updateGolden")) {
            Files.writeString(GOLDEN, exported);
        }
        assertEquals(Files.readString(GOLDEN), exported);
    }

    @Test
    void vetsAgainstTheMetaSchema() throws Exception {
        CueVet.Result r = CueVet.vet(exported(), tmp);
        assertEquals(0, r.exitCode(), r.output());
    }
}
