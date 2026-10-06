package dev.docuconf.contract;

/** A config-file overlay in a contract (SPEC §4.7, {@code #Overlay}). */
public final class OverlaySpec {
    /** The overlay name, a DNS label. */
    public String name;
    /** What it is for, or {@code null}. */
    public String description;
    /** {@code json}, {@code yaml} or {@code toml}; always {@code yaml} for Spring. */
    public String format = "yaml";
    /** Where the app reads it. */
    public String path;
    /** How a configKey splits into nested keys; {@code .} for Spring. */
    public String keySeparator = ".";
    /** {@code restart} or {@code watch}. */
    public String reload = "restart";

    /**
     * Creates an overlay.
     *
     * @param name the name
     * @param path the path
     */
    public OverlaySpec(String name, String path) {
        this.name = name;
        this.path = path;
    }
}
