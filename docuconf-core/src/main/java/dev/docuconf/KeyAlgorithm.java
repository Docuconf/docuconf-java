package dev.docuconf;

/** Public-key algorithms a TLS certificate may use. */
public enum KeyAlgorithm {
    /** RSA keys. */
    RSA,
    /** ECDSA keys. */
    ECDSA,
    /** Ed25519 keys. */
    ED25519;

    /**
     * The name the contract uses.
     *
     * @return {@code RSA}, {@code ECDSA} or {@code Ed25519}
     */
    public String contractName() {
        return this == ED25519 ? "Ed25519" : name();
    }
}
