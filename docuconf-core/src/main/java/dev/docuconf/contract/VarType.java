package dev.docuconf.contract;

/** Variable types (SPEC §4.3). */
public enum VarType {
    /** A string. */
    STRING("string"),
    /** A 64-bit signed integer. */
    INT("int"),
    /** A finite floating-point number. */
    FLOAT("float"),
    /** A boolean. */
    BOOL("bool"),
    /** A duration, written in Go syntax in the contract. */
    DURATION("duration"),
    /** A URL with a scheme. */
    URL("url"),
    /** One of a fixed set of strings. */
    ENUM("enum"),
    /** A list of strings or integers. */
    LIST("list"),
    /** A JSON document. */
    JSON("json");

    private final String id;

    VarType(String id) {
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
    public static VarType of(String id) {
        for (VarType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown variable type " + id);
    }
}
