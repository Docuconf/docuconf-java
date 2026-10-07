package dev.docuconf.spring;

/**
 * Thrown at startup when docuconf cannot check the configuration because of how the app is built: a contract that
 * is out of date, or a library docuconf needs that is missing. The message says how to fix it;
 * {@link DocuconfSetupFailureAnalyzer} reports it without a stack trace.
 */
public class DocuconfSetupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong and how to fix it
     */
    public DocuconfSetupException(String message) {
        super(message);
    }
}
