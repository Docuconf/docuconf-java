package dev.docuconf.spring;

import dev.docuconf.check.Violation;
import java.util.List;

/**
 * Thrown at startup when the environment or a file input violates the contract. Holds every violation, each with
 * its stable code; never a secret value. {@link DocuconfFailureAnalyzer} turns it into Spring Boot's startup
 * failure report.
 */
public class DocuconfValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    /**
     * Creates the exception.
     *
     * @param violations every violation found
     */
    public DocuconfValidationException(List<Violation> violations) {
        super(message(violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Every violation found.
     *
     * @return the violations
     */
    public List<Violation> getViolations() {
        return violations;
    }

    /**
     * One line, so Spring's "cancelling refresh attempt" warning does not print every problem a second time; the
     * list is in {@link #getViolations()} and in the startup failure report.
     */
    private static String message(List<Violation> violations) {
        return "docuconf: " + violations.size() + (violations.size() == 1 ? " configuration problem"
                : " configuration problems") + " (listed in the startup failure report)";
    }
}
