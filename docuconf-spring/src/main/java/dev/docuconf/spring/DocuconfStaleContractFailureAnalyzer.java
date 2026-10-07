package dev.docuconf.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Reports a stale contract in Spring Boot's "APPLICATION FAILED TO START" output, without a stack trace. */
public class DocuconfStaleContractFailureAnalyzer extends AbstractFailureAnalyzer<DocuconfStaleContractException> {

    /** Creates the analyzer. */
    public DocuconfStaleContractFailureAnalyzer() {
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DocuconfStaleContractException cause) {
        return new FailureAnalysis(cause.getMessage(), "Rebuild the application so the contract is exported again."
                + " To only warn, set docuconf.stale-contract=warn.", cause);
    }
}
