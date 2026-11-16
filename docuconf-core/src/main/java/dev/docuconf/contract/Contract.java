package dev.docuconf.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A configuration contract (SPEC §4): metadata, variables, file inputs, overlays, profiles and roles. */
public final class Contract {
    /** The language id written to {@code metadata.generator.language}. */
    public static final String LANGUAGE = "java";

    /** The service name, a DNS label. */
    public String name;
    /** The app version the contract was exported from, or {@code null}. */
    public String appVersion;
    /** The SDK package name. */
    public String sdk = "docuconf-spring";
    /** The SDK version. */
    public String sdkVersion = "0.0.0";
    /** Variables, sorted by name. */
    public final Map<String, VarSpec> vars = new TreeMap<>();
    /** File inputs, sorted by name. */
    public final Map<String, FileSpec> files = new TreeMap<>();
    /** Config-file overlays, sorted by name. */
    public final Map<String, OverlaySpec> overlays = new TreeMap<>();
    /** Profiles, or {@code null}. */
    public Profiles profiles;
    /**
     * The roles the image runs as (SPEC §4.9), or {@code null} when the contract has none. Each workload names its
     * role in {@code DOCUCONF_ROLE}.
     */
    public List<String> roles;
    /**
     * The well-known fragments the contract includes, as {@code name@major} (SPEC §4.8). Their variables are inline
     * in {@link #vars}; the list is provenance, for docs.
     */
    public final List<String> includes = new ArrayList<>();
    /**
     * Problems found reading the contract's JSON that the model cannot hold, such as a malformed {@code requiredIf};
     * {@link DeclarationValidator} reports them.
     */
    public final List<String> readErrors = new ArrayList<>();
}
