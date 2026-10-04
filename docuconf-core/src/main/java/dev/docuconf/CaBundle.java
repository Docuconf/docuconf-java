package dev.docuconf;

import dev.docuconf.check.Pem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.TrustManagerFactory;

/** A mounted PEM bundle of CA certificates. */
public final class CaBundle {

    private final Path path;

    /**
     * Creates a handle for a file.
     *
     * @param path the bundle
     */
    public CaBundle(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /**
     * The bundle's location.
     *
     * @return the path
     */
    public Path path() {
        return path;
    }

    /**
     * Reads every certificate in the bundle.
     *
     * @return the certificates
     * @throws GeneralSecurityException if one does not parse
     */
    public List<X509Certificate> certificates() throws GeneralSecurityException {
        return Pem.certificates(TlsKeyPair.read(path));
    }

    /**
     * A trust store holding the bundle's certificates.
     *
     * @return the key store
     * @throws GeneralSecurityException if the bundle does not parse
     */
    public KeyStore trustStore() throws GeneralSecurityException {
        return trustStore(certificates());
    }

    /**
     * Trust managers that trust only this bundle.
     *
     * @return a factory initialized with the bundle
     * @throws GeneralSecurityException if the bundle does not parse
     */
    public TrustManagerFactory trustManagerFactory() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore());
        return tmf;
    }

    static KeyStore trustStore(List<X509Certificate> certificates) throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try {
            store.load(null, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int i = 0;
        for (X509Certificate c : certificates) {
            store.setCertificateEntry("ca-" + i++, c);
        }
        return store;
    }

    @Override
    public String toString() {
        return "CaBundle[" + path + "]";
    }
}
