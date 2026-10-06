package dev.docuconf.spring;

import dev.docuconf.contract.OverlaySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * One config-file overlay as a property source (SPEC §4.7), read with Spring Boot's own YAML loader, so keys,
 * lists and values come out exactly as they would from an {@code application.yml}. A missing file is an empty
 * source; a malformed one is an empty source with an {@link #error()}, which the startup check reports.
 */
final class DocuconfOverlaySource extends MapPropertySource {

    private static final Pattern LINE = Pattern.compile("line (\\d+), column (\\d+)");

    private final OverlaySpec spec;
    private final Path path;
    private final byte[] digest;
    private final String error;

    private DocuconfOverlaySource(OverlaySpec spec, Path path, Map<String, Object> values, byte[] digest,
            String error) {
        super(name(spec), Collections.unmodifiableMap(values));
        this.spec = spec;
        this.path = path;
        this.digest = digest;
        this.error = error;
    }

    /**
     * The property source name for an overlay.
     *
     * @param spec the overlay
     * @return such as {@code docuconfOverlay [platform]}
     */
    static String name(OverlaySpec spec) {
        return "docuconfOverlay [" + spec.name + "]";
    }

    /**
     * Reads an overlay.
     *
     * @param spec the overlay
     * @param path where it is, after {@code DOCUCONF_FILE_ROOT}
     * @return the source
     */
    static DocuconfOverlaySource load(OverlaySpec spec, Path path) {
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return new DocuconfOverlaySource(spec, path, Map.of(), null, null);
        } catch (IOException | SecurityException e) {
            if (!Files.exists(path)) {
                return new DocuconfOverlaySource(spec, path, Map.of(), null, null);
            }
            return new DocuconfOverlaySource(spec, path, Map.of(), new byte[0], "cannot be read");
        }
        byte[] digest = digest(content);
        try {
            List<PropertySource<?>> documents = new YamlPropertySourceLoader().load(name(spec),
                    new ByteArrayResource(content, path.toString()));
            Map<String, Object> values = new LinkedHashMap<>();
            for (PropertySource<?> document : documents) {
                if (document instanceof EnumerablePropertySource<?> e) {
                    for (String key : e.getPropertyNames()) {
                        values.put(key, e.getProperty(key)); // a later document wins, as in application.yml
                    }
                }
            }
            return new DocuconfOverlaySource(spec, path, values, digest, null);
        } catch (IOException | RuntimeException e) {
            // Never the parser's message: it quotes the file, and a misplaced secret could be in it.
            Matcher m = LINE.matcher(String.valueOf(e.getMessage()));
            return new DocuconfOverlaySource(spec, path, Map.of(), digest,
                    "is not valid YAML" + (m.find() ? " (line " + m.group(1) + ", column " + m.group(2) + ")" : ""));
        }
    }

    /**
     * The SHA-256 of a file's content, or {@code null} when it is missing or unreadable.
     *
     * @param path the file
     * @return the digest
     */
    static byte[] digestOf(Path path) {
        try {
            return digest(Files.readAllBytes(path));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static byte[] digest(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    OverlaySpec spec() {
        return spec;
    }

    Path path() {
        return path;
    }

    /** The content's SHA-256 when it was read, or {@code null} when the file was missing. */
    byte[] digest() {
        return digest;
    }

    /** Why the file could not be used, or {@code null}. */
    String error() {
        return error;
    }
}
