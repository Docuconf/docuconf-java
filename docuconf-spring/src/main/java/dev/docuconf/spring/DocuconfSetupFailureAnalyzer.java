package dev.docuconf.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Reports a {@link DocuconfSetupException} in Spring Boot's "APPLICATION FAILED TO START" output. */
public class DocuconfSetupFailureAnalyzer extends AbstractFailureAnalyzer<DocuconfSetupException> {

    /** Creates the analyzer. */
    public DocuconfSetupFailureAnalyzer() {
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DocuconfSetupException cause) {
        String action = cause instanceof DocuconfStaleContractException
                ? "Rebuild the application so the contract is exported again. To only warn, set"
                        + " docuconf.stale-contract=warn."
                : "Fix the build as described above.";
        return new FailureAnalysis(cause.getMessage(), action, cause);
    }
}
