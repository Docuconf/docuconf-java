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
 * {@code docuconf conformance export --golden golden.cue} accepts.
 *
 * <p>One difference is known and asserted exactly, so any other fails the test: Spring's export always carries
 * each variable's {@code configKey}, its real binding key ({@code app-name}), which the golden contract leaves out,
 * except for {@code APP_NAME}, where it has the .NET-style key {@code App:Name} that no Spring property can have. A
 * Spring {@code configKey} is the key a config-file overlay is rendered at (SPEC §4.7), so docuconf-spring cannot
 * drop or rename it. Every other field matches the golden contract as data.
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
        assertEquals(KNOWN_DIFFERENCES, differences, "docuconf conformance export (exit " + status + "):\n" + out
                + "\nexported:\n" + c.contractCue());
        assertEquals(KNOWN_DIFFERENCES.isEmpty(), status == 0, out);
    }

    /** Spring's configKey on every variable; see the class comment. */
    static final List<String> KNOWN_DIFFERENCES = List.of(
            "vars.ALLOWED_ORIGINS.configKey: not in the golden contract (exported \"allowed-origins\")",
            "vars.APP_NAME.configKey: golden \"App:Name\", exported \"app-name\"",
            "vars.DATABASE_URL.configKey: not in the golden contract (exported \"database-url\")",
            "vars.DEBUG.configKey: not in the golden contract (exported \"debug\")",
            "vars.LOG_LEVEL.configKey: not in the golden contract (exported \"log-level\")",
            "vars.OLD_PORT.configKey: not in the golden contract (exported \"old-port\")",
            "vars.PARTNER_PASSWORD.configKey: not in the golden contract (exported \"partner-password\")",
            "vars.PORT.configKey: not in the golden contract (exported \"port\")",
            "vars.RATE_LIMITS.configKey: not in the golden contract (exported \"rate-limits\")",
            "vars.REQUEST_TIMEOUT.configKey: not in the golden contract (exported \"request-timeout\")",
            "vars.SHARDS.configKey: not in the golden contract (exported \"shards\")",
            "vars.TRACE_RATIO.configKey: not in the golden contract (exported \"trace-ratio\")",
            "vars.WEBHOOK_KEYS.configKey: not in the golden contract (exported \"webhook-keys\")");

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
