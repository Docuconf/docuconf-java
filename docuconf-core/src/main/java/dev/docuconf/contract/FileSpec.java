package dev.docuconf.contract;

import java.util.List;
import java.util.Map;

/** One file input in a contract (SPEC §4.6). Fields that do not apply to the type are {@code null}. */
public final class FileSpec {
    /** The input name, a DNS label. */
    public String name;
    /** The type. */
    public FileType type;
    /** The description, at least five characters. */
    public String description;
    /** Whether the platform must supply it. */
    public boolean required;
    /** Whether its content is secret. Always true for TLS and keystores. */
    public boolean secret;
    /** Docs group. */
    public String group;
    /** Deprecation, if any. */
    public Deprecation deprecated;
    /** Where the app reads it: a directory for TLS, a file otherwise. */
    public String path;
    /** A variable the platform sets to {@link #path}. */
    public String pathEnv;
    /** {@code restart} or {@code watch}. */
    public String reload = "restart";
    /** Size limit in bytes. */
    public Long maxSize;
    /** Config: {@code json}, {@code yaml} or {@code toml}; keystore: {@code pkcs12} or {@code jks}. */
    public String format;
    /** Config: JSON Schema of the bound type. */
    public Map<String, Object> schema;
    /** TLS: names the certificate must cover. */
    public List<String> dnsNames;
    /** TLS: allowed key algorithms. */
    public List<String> keyAlgorithms;
    /** TLS: least remaining validity, Go syntax. */
    public String minRemaining;
    /** TLS: whether ca.crt is required and the chain checked. */
    public boolean requireCA;
    /** CA bundle: least number of certificates. */
    public Integer minCertificates;
    /** Keystore: the secret variable holding the password. */
    public String passwordVar;
    /** Text: RE2 pattern, matched anywhere. */
    public String pattern;
    /** Text: least length. */
    public Integer minLength;
    /** Text: greatest length. */
    public Integer maxLength;

    /** Creates an empty spec. */
    public FileSpec() {
    }

    /**
     * Creates a spec.
     *
     * @param name the input name
     * @param type the type
     * @param description the description
     * @param path the path
     */
    public FileSpec(String name, FileType type, String description, String path) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.path = path;
        this.secret = type == FileType.TLS || type == FileType.KEYSTORE;
    }
}
