package dev.docuconf.contract;

import java.util.Locale;
import java.util.regex.Pattern;

/** Spring Boot property names and the environment variable names its relaxed binding maps them from. */
public final class Names {

    /** Contract variable names (SPEC §4.2). */
    public static final Pattern ENV_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** File input names (a DNS label, since they name volumes). */
    public static final Pattern INPUT_NAME = Pattern.compile("^[a-z]([-a-z0-9]{0,40}[a-z0-9])?$");

    /** Service names. */
    public static final Pattern SERVICE_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    /** Names that look like feature flags (SPEC §10). */
    public static final Pattern FEATURE_FLAG = Pattern.compile("^(FF|FEATURE|FEATURE_FLAG|ENABLE)_");

    private Names() {
    }

    /**
     * Spring's dashed form of a Java property name: {@code databaseUrl} becomes {@code database-url}.
     *
     * @param name a Java property name
     * @return the dashed form
     */
    public static String dashed(String name) {
        StringBuilder result = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            ch = ch != '_' ? ch : '-';
            if (Character.isUpperCase(ch) && result.length() > 0 && result.charAt(result.length() - 1) != '-') {
                result.append('-');
            }
            result.append(Character.toLowerCase(ch));
        }
        return result.toString();
    }

    /**
     * The environment variable Spring Boot binds to a property: dots become underscores, dashes are dropped and
     * the result is upper case. {@code billing.database-url} becomes {@code BILLING_DATABASEURL}.
     *
     * @param configKey a dashed property name
     * @return the variable name
     */
    public static String envName(String configKey) {
        return configKey.replace("-", "").replace('.', '_').toUpperCase(Locale.ROOT);
    }

    /**
     * Spring's canonical form of a property name, for comparing relaxed names: lower case, no dashes or
     * underscores within elements. {@code billing.databaseUrl}, {@code billing.database_url} and
     * {@code billing.database-url} are all {@code billing.databaseurl}.
     *
     * @param key a property name in any relaxed form
     * @return the canonical form
     */
    public static String canonical(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * kebab-case of a Java name, for file input names: {@code servingTls} becomes {@code serving-tls}.
     *
     * @param name a Java property name
     * @return the kebab-case name
     */
    public static String kebab(String name) {
        return dashed(name);
    }
}
