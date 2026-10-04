package dev.docuconf.contract;

import java.util.Map;
import java.util.TreeMap;

/** A configuration contract (SPEC §4): metadata, variables, file inputs and profiles. */
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
    /** Profiles, or {@code null}. */
    public Profiles profiles;
}
