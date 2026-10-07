package dev.docuconf.spring;

/**
 * Thrown at startup when the contract in the jar was exported from different {@code application*.yml} files than
 * the ones the jar ships, so the platform would check values against stale defaults. Rebuild to fix it.
 */
public class DocuconfStaleContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is out of date and how to rebuild
     */
    public DocuconfStaleContractException(String message) {
        super(message);
    }
}
