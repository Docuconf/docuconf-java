package dev.docuconf.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

/**
 * Runs {@code cue vet -c} on a contract against the docuconf meta-schema. Skips the test when cue or the
 * meta-schema is not available, unless {@code DOCUCONF_REQUIRE_VET=1}.
 *
 * <p>The meta-schema is found at {@code $DOCUCONF_SPEC_CUE}, else at {@code ../docuconf-go/spec/cue} next to
 * this repository.
 */
public final class CueVet {

    private CueVet() {
    }

    /** The result of running cue. */
    public record Result(int exitCode, String output) {
    }

    public static Result vet(String contractCue, Path work) throws IOException, InterruptedException {
        Path cue = cueBinary();
        Path spec = specDir();
        boolean require = "1".equals(System.getenv("DOCUCONF_REQUIRE_VET"));
        if (cue == null || spec == null) {
            if (require) {
                throw new AssertionError("cue or the meta-schema is missing (cue=" + cue + ", spec=" + spec + ")");
            }
            Assumptions.abort("cue or the docuconf meta-schema is not available; skipping cue vet");
        }
        copy(spec.resolve("cue.mod"), work.resolve("cue.mod"));
        copy(spec.resolve("contract"), work.resolve("contract"));
        Files.createDirectories(work.resolve("svc"));
        Files.writeString(work.resolve("svc/contract.cue"), contractCue);
        Process p = new ProcessBuilder(cue.toString(), "vet", "-c", "./svc").directory(work.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    /** Runs {@code cue export --out json} on the contract (after a successful vet in the same directory). */
    public static String export(Path work) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cueBinary().toString(), "export", "--out", "json", "./svc")
                .directory(work.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new AssertionError("cue export failed:\n" + out);
        }
        return out;
    }

    public static Path cueBinary() {
        String env = System.getenv("CUE");
        if (env != null && Files.isExecutable(Path.of(env))) {
            return Path.of(env);
        }
        Path home = Path.of(System.getProperty("user.home"), "go", "bin", "cue");
        if (Files.isExecutable(home)) {
            return home;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path c = Path.of(dir, "cue");
                if (Files.isExecutable(c)) {
                    return c;
                }
            }
        }
        return null;
    }

    public static Path specDir() {
        String env = System.getenv("DOCUCONF_SPEC_CUE");
        if (env != null && Files.isDirectory(Path.of(env, "contract"))) {
            return Path.of(env);
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docuconf-go/spec/cue");
            if (Files.isDirectory(candidate.resolve("contract"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
