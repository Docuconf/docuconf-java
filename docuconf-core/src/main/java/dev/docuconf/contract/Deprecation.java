package dev.docuconf.contract;

/**
 * A deprecated input.
 *
 * @param message why, and what to do instead
 * @param replacedBy the input that replaces it, or {@code null}
 */
public record Deprecation(String message, String replacedBy) {
}
