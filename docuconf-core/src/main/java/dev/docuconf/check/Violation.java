package dev.docuconf.check;

/**
 * One configuration problem.
 *
 * @param code the stable code
 * @param input the environment variable or file input name
 * @param message what is wrong; never contains a secret value
 */
public record Violation(Code code, String input, String message) {

    @Override
    public String toString() {
        return "[" + code.id() + "] " + input + ": " + message;
    }
}
