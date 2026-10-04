package dev.docuconf.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Writes startup violations to the Kubernetes termination log, so {@code kubectl describe pod} shows why the
 * container stopped.
 */
public final class TerminationLog {

    /** The variable that overrides the termination log path. */
    public static final String ENV = "DOCUCONF_TERMINATION_LOG";

    /** The default termination message path. */
    public static final String DEFAULT_PATH = "/dev/termination-log";

    private TerminationLog() {
    }

    /**
     * Writes the violations, one per line, when the log exists (or its override is set). Failures are ignored.
     *
     * @param violations the violations
     * @param env looks up environment variables
     * @return the path written, or {@code null}
     */
    public static Path write(List<Violation> violations, Function<String, String> env) {
        String override = env.apply(ENV);
        Path path = Path.of(override != null && !override.isEmpty() ? override : DEFAULT_PATH);
        if (override == null && !Files.exists(path)) {
            return null;
        }
        StringBuilder b = new StringBuilder("docuconf: invalid configuration\n");
        for (Violation v : violations) {
            b.append(v).append('\n');
        }
        // The kubelet keeps at most 4096 bytes.
        byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) {
            bytes = java.util.Arrays.copyOf(bytes, 4096);
        }
        try {
            Files.write(path, bytes);
            return path;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
