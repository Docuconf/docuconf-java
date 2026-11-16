package dev.docuconf.contract;

/**
 * A condition that makes an input required (SPEC §4.2.1): while the variable {@code var} has {@code value}.
 *
 * @param var the condition variable: a string, int, bool or enum variable that is not secret
 * @param value the value it is compared with: a {@code String}, {@code Long} or {@code Boolean}
 */
public record RequiredIf(String var, Object value) {

    /**
     * Whether a variable's loaded value is the condition's value: a string or enum compared as written, an int as
     * a number, a bool as a bool.
     *
     * @param loaded the condition variable's typed value, or {@code null} when it has none
     * @return whether the condition holds
     */
    public boolean holdsFor(Object loaded) {
        return loaded != null && value.equals(loaded);
    }

    /**
     * The condition as messages write it, the value as JSON: {@code LOG_FORMAT is "json"}.
     *
     * @return the text
     */
    @Override
    public String toString() {
        return var + " is " + Json.write(value);
    }
}
