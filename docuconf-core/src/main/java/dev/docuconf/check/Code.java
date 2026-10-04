package dev.docuconf.check;

/** Stable error codes (SPEC §11.2 item 5). */
public enum Code {
    /** A required input is not set. */
    MISSING_REQUIRED,
    /** A value does not parse as its type. */
    INVALID_TYPE,
    /** A number, duration or length is outside its bounds. */
    OUT_OF_RANGE,
    /** A string or text file does not match its pattern. */
    PATTERN_MISMATCH,
    /** A value is not one of the enum's values. */
    NOT_IN_ENUM,
    /** A URL's scheme is not allowed. */
    INVALID_SCHEME,
    /** A list has too few items. */
    TOO_FEW_ITEMS,
    /** A list has too many items. */
    TOO_MANY_ITEMS,
    /** A required file does not exist. */
    FILE_MISSING,
    /** A file exists but cannot be read. */
    FILE_UNREADABLE,
    /** A file is larger than its limit. */
    FILE_TOO_LARGE,
    /** A file does not parse, or a CA bundle has too few certificates. */
    FILE_MALFORMED,
    /** A config file parses but does not fit the app's type. */
    SCHEMA_MISMATCH,
    /** A certificate is expired, not yet valid, uses a disallowed key algorithm or does not chain. */
    CERTIFICATE_INVALID,
    /** A certificate has less validity left than required. */
    CERTIFICATE_EXPIRING,
    /** A certificate does not cover a required name. */
    CERTIFICATE_NAME_MISMATCH,
    /** A private key does not parse or does not match its certificate. */
    KEY_MISMATCH,
    /** A keystore does not open with its password. */
    KEYSTORE_UNREADABLE;

    /**
     * The code as the spec writes it.
     *
     * @return such as {@code missing_required}
     */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return id();
    }
}
