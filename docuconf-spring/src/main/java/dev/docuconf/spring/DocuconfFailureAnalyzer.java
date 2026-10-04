package dev.docuconf.spring;

import dev.docuconf.check.Violation;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Reports every docuconf violation in Spring Boot's "APPLICATION FAILED TO START" output. */
public class DocuconfFailureAnalyzer extends AbstractFailureAnalyzer<DocuconfValidationException> {

    /** Creates the analyzer. */
    public DocuconfFailureAnalyzer() {
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DocuconfValidationException cause) {
        StringBuilder description = new StringBuilder("The configuration does not satisfy the docuconf contract (")
                .append(cause.getViolations().size()).append(cause.getViolations().size() == 1 ? " problem" : " problems")
                .append("):\n");
        for (Violation v : cause.getViolations()) {
            description.append("\n    [").append(v.code().id()).append("] ").append(v.input()).append(": ")
                    .append(v.message());
        }
        String action = "Set or fix the environment variables and files listed above. Codes are defined in the"
                + " docuconf spec (section 11.2). For local runs, DOCUCONF_FILE_ROOT=./dev reads file inputs from"
                + " ./dev; docuconf.enabled=false skips the check (for tests and build-time tasks).";
        return new FailureAnalysis(description.toString(), action, cause);
    }
}
