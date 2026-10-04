package dev.docuconf.contract;

/** File input types (SPEC §4.6). */
public enum FileType {
    /** A structured config file. */
    CONFIG("config"),
    /** A TLS key pair directory. */
    TLS("tls"),
    /** A PEM bundle of CA certificates. */
    CA_BUNDLE("caBundle"),
    /** A PKCS#12 or JKS keystore. */
    KEYSTORE("keystore"),
    /** A text file. */
    TEXT("text"),
    /** Opaque bytes. */
    BINARY("binary");

    private final String id;

    FileType(String id) {
        this.id = id;
    }

    /**
     * The name used in contracts.
     *
     * @return the contract name
     */
    public String id() {
        return id;
    }

    /**
     * Looks a type up by its contract name.
     *
     * @param id the contract name
     * @return the type
     */
    public static FileType of(String id) {
        for (FileType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown file type " + id);
    }
}
