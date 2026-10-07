package dev.docuconf;

import java.util.Locale;

/**
 * How an enum's constants are spelled in the contract, which is what the platform checks values against.
 *
 * <p>The app itself stays as lenient as Spring: at startup any spelling Spring's binder accepts for a constant
 * ({@code WARN}, {@code warn}, {@code Warn}) is valid, wherever it comes from.
 */
public enum EnumCase {

    /** The constant names as declared: {@code IN_MEMORY}. */
    AS_DECLARED,

    /** Lower case: {@code in_memory}. */
    LOWER,

    /** Lower case with dashes, as Spring writes enum values in {@code application.yml}: {@code in-memory}. */
    KEBAB;

    /**
     * Spells a constant name in this case.
     *
     * @param constant the constant name
     * @return the contract value
     */
    public String apply(String constant) {
        return switch (this) {
            case AS_DECLARED -> constant;
            case LOWER -> constant.toLowerCase(Locale.ROOT);
            case KEBAB -> constant.toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }
}
