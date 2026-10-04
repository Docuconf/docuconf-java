package dev.docuconf.contract;

import java.util.Map;
import java.util.TreeMap;

/** Profile files baked into the image (SPEC §4.4). */
public final class Profiles {
    /** The variable that selects the profile, such as {@code SPRING_PROFILES_ACTIVE}. */
    public String selector;
    /** The profile in effect when the selector is unset. */
    public String defaultProfile;
    /** Values per profile, keyed by profile then variable name. */
    public final Map<String, Map<String, Object>> defaults = new TreeMap<>();
}
