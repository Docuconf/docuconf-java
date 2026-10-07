package dev.docuconf.spring;

import java.lang.reflect.Type;
import java.util.Locale;
import org.springframework.boot.SpringBootVersion;
import org.springframework.util.ClassUtils;

/**
 * Jackson, whichever major version the app has: Jackson 3 on Spring Boot 4, Jackson 2 on Spring Boot 3. Reads
 * {@code @Json} variables and {@code @ConfigFile} files strictly (unknown properties and trailing content are
 * errors).
 */
interface JsonMapper {

    /** The text does not parse. */
    final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;
        final int line;
        final int column;

        Malformed(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }
    }

    /** The text parses but does not fit the type. */
    final class Mismatch extends Exception {
        private static final long serialVersionUID = 1L;
        final String path;

        Mismatch(String message, String path) {
            super(message);
            this.path = path;
        }
    }

    /**
     * Parses a document into a tree.
     *
     * @return the tree, or {@code null} when the document is empty
     */
    Object readTree(byte[] content) throws Malformed;

    /** Binds a tree to a type. */
    Object convert(Object tree, Type type) throws Mismatch;

    /** Parses and binds a JSON text. */
    default Object read(String json, Type type) throws Malformed, Mismatch {
        Object tree = readTree(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return tree == null ? null : convert(tree, type);
    }

    /**
     * A mapper for a format: {@code json}, {@code yaml} or {@code toml}.
     *
     * @throws DocuconfSetupException when the app has no Jackson, or not the module for the format
     */
    static JsonMapper forFormat(String format) {
        return forFormat(format, JsonMapper.class.getClassLoader());
    }

    /** {@link #forFormat(String)}, looking for Jackson in a given class loader. */
    static JsonMapper forFormat(String format, ClassLoader cl) {
        boolean jackson3 = ClassUtils.isPresent("tools.jackson.databind.ObjectMapper", cl);
        boolean jackson2 = ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper", cl);
        String boot = SpringBootVersion.getVersion();
        boolean preferJackson2 = boot != null && boot.startsWith("3.");
        String f = format == null ? "json" : format.toLowerCase(Locale.ROOT);
        if (jackson3 && (!jackson2 || !preferJackson2)) {
            requireModule(f, "tools.jackson.dataformat." + f + "." + f.toUpperCase(Locale.ROOT) + "Mapper",
                    "tools.jackson.dataformat:jackson-dataformat-" + f, cl);
            return new Jackson3Mapper(f);
        }
        if (jackson2) {
            requireModule(f, "com.fasterxml.jackson.dataformat." + f + "." + (f.equals("yaml") ? "YAMLMapper"
                    : "TomlMapper"), "com.fasterxml.jackson.dataformat:jackson-dataformat-" + f, cl);
            return new Jackson2Mapper(f);
        }
        throw new DocuconfSetupException("docuconf: @Json variables and @ConfigFile files are read with Jackson,"
                + " which is not on the class path; add tools.jackson.core:jackson-databind (Spring Boot 4) or"
                + " com.fasterxml.jackson.core:jackson-databind (Spring Boot 3), for example with"
                + " spring-boot-starter-json");
    }

    private static void requireModule(String format, String className, String artifact, ClassLoader cl) {
        if (!format.equals("json") && !ClassUtils.isPresent(className.replace("TOMLMapper", "TomlMapper"), cl)) {
            throw new DocuconfSetupException("docuconf: reading " + format.toUpperCase(Locale.ROOT)
                    + " config files needs " + artifact + " on the class path");
        }
    }
}
