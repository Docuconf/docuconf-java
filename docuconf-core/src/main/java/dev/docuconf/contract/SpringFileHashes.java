package dev.docuconf.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The {@code application*.yml} files a contract was exported from, by SHA-256. The annotation processor bakes
 * their values into the contract (defaults, profile defaults, the secrets check), but neither javac nor Gradle
 * recompiles when only a resource changes, so the contract can go stale. The hashes recorded next to the contract
 * let the build plugin and the startup check notice.
 */
public final class SpringFileHashes {

    /** File names Spring reads from the root of the class path and from {@code config/}. */
    public static final Pattern NAME = Pattern.compile("(config/)?application(-[^/.]+)?\\.(yml|yaml|properties)");

    /** The base files, which exist under these names whatever the profiles. */
    public static final List<String> BASE_NAMES = List.of("application.yml", "application.yaml",
            "application.properties", "config/application.yml", "config/application.yaml",
            "config/application.properties");

    private SpringFileHashes() {
    }

    /**
     * The SHA-256 of a file's content, in lower-case hex.
     *
     * @param content the bytes
     * @return the hash
     */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The SHA-256 of a text, in lower-case hex.
     *
     * @param text the text, as UTF-8
     * @return the hash
     */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compares the recorded hashes with the files as they are now.
     *
     * @param recorded the hashes in the contract, by file name relative to the resources root
     * @param current reads a file now: its content, or {@code null} when it is absent
     * @param present the names of the files that exist now, when they can be listed; else {@code null}
     * @return one line per difference, such as {@code application.yml changed}; empty when up to date
     */
    public static List<String> differences(Map<String, String> recorded, Function<String, byte[]> current,
            Iterable<String> present) {
        List<String> out = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(recorded.keySet());
        if (present != null) {
            present.forEach(names::add);
        } else {
            names.addAll(BASE_NAMES);
        }
        for (String name : names) {
            byte[] now = current.apply(name);
            String was = recorded.get(name);
            if (was == null && now != null) {
                out.add(name + " was added");
            } else if (was != null && now == null) {
                out.add(name + " was removed");
            } else if (was != null && !was.equals(sha256(now))) {
                out.add(name + " changed");
            }
        }
        return out;
    }
}
