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
     * The other environment variable Spring Boot binds to a property (its "legacy" form): dots and dashes both
     * become underscores. {@code billing.database-url} becomes {@code BILLING_DATABASE_URL}.
     *
     * @param configKey a dashed property name
     * @return the variable name
     */
    public static String underscoredEnvName(String configKey) {
        return configKey.replace('-', '_').replace('.', '_').toUpperCase(Locale.ROOT);
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
     * The edit distance between two names (insertions, deletions, substitutions and swaps of neighbours), for
     * "did you mean" hints.
     *
     * @param a one name
     * @param b the other
     * @param limit the distance at which to stop counting
     * @return the distance, or {@code limit} when it is at least that
     */
    public static int editDistance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) >= limit) {
            return limit;
        }
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return Math.min(d[a.length()][b.length()], limit);
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
