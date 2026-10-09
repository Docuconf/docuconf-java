package dev.docuconf.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared export check (SPEC §11.2 item 3, §12): the conformance suite's export fixture, declared with
 * {@code @ConfigurationProperties} in {@code src/test/resources/export-fixture}, exports to a contract that
 * {@code docuconf conformance export --golden golden.cue} accepts with no differences.
 *
 * <p>{@code golden.cue} is found next to {@code DOCUCONF_CONFORMANCE} ({@code ../export/golden.cue}), else in
 * {@code $DOCUCONF_GO_DIR/conformance/export}; the docuconf CLI through {@code DOCUCONF_CLI}, else {@code docuconf}
 * on the {@code PATH}. When either is missing the test is skipped, unless {@code DOCUCONF_REQUIRE_CONFORMANCE=1}.
 */
class ExportConformanceTest {

    static final Path FIXTURE = Path.of("src/test/resources/export-fixture/FixtureProperties.java");
    static final Path APPLICATION_YML = Path.of("src/test/resources/export-fixture/application.yml");

    @TempDir
    Path tmp;

    @Test
    void theExportFixtureMatchesTheGoldenContract() throws Exception {
        Compilation c = Compilation.compile(tmp, Map.of("application.yml", Files.readString(APPLICATION_YML)),
                Map.of("fixture.FixtureProperties",
                Files.readString(FIXTURE, StandardCharsets.UTF_8)), "-Adocuconf.appVersion=1.0.0");
        assertTrue(c.success, c.allErrors());
        Path exported = tmp.resolve("exported.cue");
        Files.writeString(exported, c.contractCue());

        Path golden = golden();
        String cli = cli();
        if (golden == null || cli == null) {
            String why = "the export check needs docuconf-go's conformance/export/golden.cue and the docuconf CLI"
                    + " (golden=" + golden + ", cli=" + cli + ")";
            if ("1".equals(System.getenv("DOCUCONF_REQUIRE_CONFORMANCE"))) {
                fail(why);
            }
            Assumptions.abort(why);
        }
        Process p = new ProcessBuilder(cli, "conformance", "export", "--golden", golden.toString(),
                exported.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = p.waitFor();
        List<String> differences = out.lines().filter(l -> !l.startsWith("docuconf conformance:")
                && !l.endsWith(" matches " + golden)).toList();
        String report = "docuconf conformance export (exit " + status + "):\n" + out + "\nexported:\n"
                + c.contractCue();
        assertEquals(List.of(), differences, report);
        assertEquals(0, status, report);
    }

    static Path golden() {
        String cases = System.getenv("DOCUCONF_CONFORMANCE");
        if (cases != null && !cases.isEmpty()) {
            Path g = Path.of(cases).toAbsolutePath().getParent().resolve("export/golden.cue");
            if (Files.isRegularFile(g)) {
                return g;
            }
        }
        String dir = System.getenv("DOCUCONF_GO_DIR");
        if (dir != null && !dir.isEmpty()) {
            Path g = Path.of(dir, "conformance/export/golden.cue");
            if (Files.isRegularFile(g)) {
                return g;
            }
        }
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            Path g = d.resolve("docuconf-go/conformance/export/golden.cue");
            if (Files.isRegularFile(g)) {
                return g;
            }
        }
        return null;
    }

    static String cli() throws IOException {
        String env = System.getenv("DOCUCONF_CLI");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, "docuconf");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        Path home = Path.of(System.getProperty("user.home"), "go", "bin", "docuconf");
        return Files.isExecutable(home) ? home.toString() : null;
    }
}
